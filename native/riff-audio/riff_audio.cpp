#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <avrt.h>
#include <jni.h>
#include <SoundTouch.h>
#include <cmath>
#include <exception>
#include <stdint.h>
#include <math.h>
#include <string>
#include <errno.h>
#include <vector>
#include <atomic>
#include <mutex>
#include <memory>
#include <unordered_map>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/channel_layout.h>
}

static void fail(JNIEnv* e,const char* msg){if(!e->ExceptionCheck())e->ThrowNew(e->FindClass("java/io/IOException"),msg);}
struct Decoder {
    AVFormatContext* format=nullptr; AVCodecContext* codec=nullptr; SwrContext* resample=nullptr;
    AVPacket* packet=nullptr; AVFrame* frame=nullptr; int stream=-1; int rate=48000; int channels=2;
    std::vector<float> pending; size_t cursor=0; bool eof=false,flushed=false; int64_t position=0;
    std::mutex mutex; std::wstring sourcePath;
    JavaVM* vm=nullptr;JNIEnv* env=nullptr;jobject reader=nullptr;jbyteArray scratch=nullptr;
    jmethodID readMethod=nullptr,lengthMethod=nullptr;AVIOContext* io=nullptr;int64_t ioPosition=0;
    ~Decoder(){av_frame_free(&frame);av_packet_free(&packet);swr_free(&resample);avcodec_free_context(&codec);avformat_close_input(&format);
        if(io){av_freep(&io->buffer);avio_context_free(&io);}if(vm){JNIEnv* e=nullptr;bool attach=vm->GetEnv((void**)&e,JNI_VERSION_1_6)!=JNI_OK;if(attach)vm->AttachCurrentThread((void**)&e,nullptr);if(reader)e->DeleteGlobalRef(reader);if(scratch)e->DeleteGlobalRef(scratch);if(attach)vm->DetachCurrentThread();}}

};
static std::mutex handlesMutex;static std::atomic<int64_t> nextHandle{1};
static std::unordered_map<int64_t,std::shared_ptr<Decoder>> decoders;
static std::shared_ptr<Decoder> decoder(JNIEnv* e,jlong id){std::lock_guard<std::mutex> l(handlesMutex);auto i=decoders.find(id);if(i==decoders.end()){fail(e,"Decoder handle is closed");return {};}return i->second;}
static jlong finishDecoder(JNIEnv* e,std::shared_ptr<Decoder> d,int status){
    if(e->ExceptionCheck())return 0;
    if(status<0||avformat_find_stream_info(d->format,nullptr)<0){fail(e,"Unsupported or corrupt audio container");return 0;}
    const AVCodec* codec=nullptr;d->stream=av_find_best_stream(d->format,AVMEDIA_TYPE_AUDIO,-1,-1,&codec,0);
    if(d->stream<0||!codec){fail(e,"No supported audio stream");return 0;}
    d->codec=avcodec_alloc_context3(codec);
    if(!d->codec||avcodec_parameters_to_context(d->codec,d->format->streams[d->stream]->codecpar)<0||avcodec_open2(d->codec,codec,nullptr)<0){fail(e,"Audio decoder could not open");return 0;}
    if(d->codec->sample_rate<8000||d->codec->sample_rate>384000||d->codec->ch_layout.nb_channels<1||d->codec->ch_layout.nb_channels>8){fail(e,"Unsupported audio channel/rate bounds");return 0;}
    // HE-AAC/PS may finalize sample rate and channel layout only when the first frame is decoded.
    // Configure the resampler from that frame, never from provisional codec parameters.
    d->packet=av_packet_alloc();d->frame=av_frame_alloc();if(!d->packet||!d->frame){fail(e,"Audio decode allocation failed");return 0;}
    auto id=nextHandle++;{std::lock_guard<std::mutex> l(handlesMutex);decoders[id]=d;}return id;
}
static int denyNestedOpen(AVFormatContext*,AVIOContext**,const char*,int,AVDictionary**){return AVERROR(EACCES);}
static int cacheRead(void* opaque,uint8_t* target,int length){
    auto* d=(Decoder*)opaque;int count=std::min(length,32768);
    jint n=d->env->CallIntMethod(d->reader,d->readMethod,(jlong)d->ioPosition,d->scratch,0,count);
    if(d->env->ExceptionCheck())return AVERROR_EXIT;if(n<0)return AVERROR_EOF;if(n==0||n>count)return AVERROR(EIO);
    d->env->GetByteArrayRegion(d->scratch,0,n,(jbyte*)target);d->ioPosition+=n;return n;
}
static int64_t cacheSeek(void* opaque,int64_t offset,int mode){
    auto* d=(Decoder*)opaque;jlong size=d->env->CallLongMethod(d->reader,d->lengthMethod);
    if(d->env->ExceptionCheck())return AVERROR_EXIT;if(mode==AVSEEK_SIZE)return size<0?AVERROR(ENOSYS):size;
    mode&=~AVSEEK_FORCE;int64_t next=mode==SEEK_SET?offset:mode==SEEK_CUR?d->ioPosition+offset:mode==SEEK_END&&size>=0?size+offset:-1;
    if(next<0)return AVERROR(EINVAL);d->ioPosition=next;return next;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_decoderOpenReader(JNIEnv* e,jobject,jobject reader,jint rate,jint channels){
    if(rate!=48000||channels!=2){fail(e,"Unsupported cache mix format");return 0;}
    auto d=std::make_shared<Decoder>();d->rate=rate;d->channels=channels;d->env=e;e->GetJavaVM(&d->vm);d->reader=e->NewGlobalRef(reader);
    jclass type=e->GetObjectClass(reader);d->readMethod=e->GetMethodID(type,"readAt","(J[BII)I");d->lengthMethod=e->GetMethodID(type,"length","()J");e->DeleteLocalRef(type);
    if(!d->readMethod||!d->lengthMethod)return 0;
    jbyteArray scratch=e->NewByteArray(32768);d->scratch=(jbyteArray)e->NewGlobalRef(scratch);e->DeleteLocalRef(scratch);
    auto* bytes=(uint8_t*)av_malloc(32768);if(!bytes){fail(e,"Cache IO allocation failed");return 0;}
    d->io=avio_alloc_context(bytes,32768,0,d.get(),cacheRead,nullptr,cacheSeek);if(!d->io){av_free(bytes);fail(e,"Cache IO context failed");return 0;}
    if(e->CallLongMethod(reader,d->lengthMethod)<0)d->io->seekable=0;
    d->format=avformat_alloc_context();if(!d->format){fail(e,"Cache demux allocation failed");return 0;}d->format->pb=d->io;d->format->flags|=AVFMT_FLAG_CUSTOM_IO;
    d->format->io_open=denyNestedOpen;
    AVDictionary* options=nullptr;av_dict_set(&options,"format_whitelist","wav,mp3,flac,mov,ogg,matroska,webm,aac",0);
    int status=avformat_open_input(&d->format,nullptr,nullptr,&options);av_dict_free(&options);return finishDecoder(e,d,status);
}
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_decoderOpen(JNIEnv* e,jobject,jstring name,jint rate,jint channels){
    if(rate<8000||rate>192000||channels!=2){fail(e,"Unsupported mix format");return 0;}
    auto d=std::make_shared<Decoder>();d->rate=rate;d->channels=channels;
    const jchar* wide=e->GetStringChars(name,nullptr);int length=e->GetStringLength(name);d->sourcePath.assign((const wchar_t*)wide,length);
    int bytes=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,(LPCWCH)wide,length,nullptr,0,nullptr,nullptr);
    std::string path(bytes, '\0');WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,(LPCWCH)wide,length,path.data(),bytes,nullptr,nullptr);e->ReleaseStringChars(name,wide);
    if(!bytes){fail(e,"Invalid source path encoding");return 0;}
    AVDictionary* options=nullptr;av_dict_set(&options,"protocol_whitelist","file",0);av_dict_set(&options,"format_whitelist","wav,mp3,flac,mov,ogg,matroska,webm,aac",0);
    int status=avformat_open_input(&d->format,path.c_str(),nullptr,&options);av_dict_free(&options);
    return finishDecoder(e,d,status);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_decoderDuration(JNIEnv* e,jobject,jlong id){auto d=decoder(e,id);if(!d)return -1;return d->format->duration==AV_NOPTS_VALUE?-1:d->format->duration/1000;}
extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_decoderRead(JNIEnv* e,jobject,jlong id,jobject buffer,jint frames){
    auto d=decoder(e,id);if(!d)return -1;d->env=e;auto* output=(float*)e->GetDirectBufferAddress(buffer);auto cap=e->GetDirectBufferCapacity(buffer);
    if(!output||frames<0||frames>1048576||cap<(int64_t)frames*d->channels*4){fail(e,"Invalid decoder output buffer");return -1;}
    std::lock_guard<std::mutex> l(d->mutex);int written=0;
    while(written<frames){
        if(d->cursor<d->pending.size()){
            auto count=std::min((size_t)(frames-written)*d->channels,d->pending.size()-d->cursor);
            memcpy(output+written*d->channels,d->pending.data()+d->cursor,count*4);d->cursor+=count;written+=(int)count/d->channels;continue;
        }
        d->pending.clear();d->cursor=0;
        int rc=avcodec_receive_frame(d->codec,d->frame);
        if(rc==0){
            if(d->frame->flags&AV_FRAME_FLAG_CORRUPT){fail(e,"Corrupt decoded audio frame");return -1;}
            if(!d->resample){
                if(d->frame->sample_rate<8000||d->frame->sample_rate>384000||d->frame->ch_layout.nb_channels<1||d->frame->ch_layout.nb_channels>8){fail(e,"Unsupported decoded frame format");return -1;}
                AVChannelLayout out;av_channel_layout_default(&out,d->channels);
                int status=swr_alloc_set_opts2(&d->resample,&out,AV_SAMPLE_FMT_FLT,d->rate,&d->frame->ch_layout,(AVSampleFormat)d->frame->format,d->frame->sample_rate,0,nullptr);av_channel_layout_uninit(&out);
                if(status<0||swr_init(d->resample)<0){fail(e,"Audio resampler could not open");return -1;}
            }
            int count=swr_get_out_samples(d->resample,d->frame->nb_samples);if(count<0||count>1048576){fail(e,"Invalid decoded frame size");return -1;}
            d->pending.resize((size_t)count*d->channels);uint8_t* dst=(uint8_t*)d->pending.data();
            count=swr_convert(d->resample,&dst,count,(const uint8_t**)d->frame->extended_data,d->frame->nb_samples);av_frame_unref(d->frame);
            if(count<0){fail(e,"Audio sample conversion failed");return -1;}d->pending.resize((size_t)count*d->channels);continue;
        }
        if(rc==AVERROR_EOF){
            if(!d->flushed&&d->resample){d->flushed=true;int count=swr_get_out_samples(d->resample,0);d->pending.resize((size_t)count*d->channels);uint8_t* dst=(uint8_t*)d->pending.data();count=swr_convert(d->resample,&dst,count,nullptr,0);d->pending.resize(count>0?(size_t)count*d->channels:0);if(count>0)continue;}break;
        }
        if(rc!=AVERROR(EAGAIN)){fail(e,"Corrupt audio frame");return -1;}
        if(d->eof)break;
        while(true){rc=av_read_frame(d->format,d->packet);if(rc<0){if(rc!=AVERROR_EOF){fail(e,"Audio source read failed");return -1;}d->eof=true;avcodec_send_packet(d->codec,nullptr);break;}if(d->packet->stream_index==d->stream)break;av_packet_unref(d->packet);}
        if(rc>=0){rc=avcodec_send_packet(d->codec,d->packet);av_packet_unref(d->packet);if(rc<0){fail(e,"Corrupt audio packet");return -1;}}
    }
    d->position+=written;return written;
}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_decoderRewind(JNIEnv* e,jobject object,jlong id){
    auto old=decoder(e,id);if(!old)return;
    jlong replacement=0;
    if(old->reader)replacement=Java_com_musicglass_desktop_NativeAudio_decoderOpenReader(e,object,old->reader,old->rate,old->channels);
    else{jstring name=e->NewString((const jchar*)old->sourcePath.data(),(jsize)old->sourcePath.size());replacement=Java_com_musicglass_desktop_NativeAudio_decoderOpen(e,object,name,old->rate,old->channels);e->DeleteLocalRef(name);}
    if(!replacement)return;
    std::lock_guard<std::mutex> l(handlesMutex);decoders[id]=decoders.at(replacement);decoders.erase(replacement);
}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_decoderClose(JNIEnv*,jobject,jlong id){std::lock_guard<std::mutex> l(handlesMutex);decoders.erase(id);}
extern "C" JNIEXPORT jstring JNICALL Java_com_musicglass_desktop_NativeAudio_version(JNIEnv* e,jobject){return e->NewStringUTF(av_version_info());}

struct PresentedSpan { int64_t deviceStart=0,deviceEnd=0,contentStart=0,contentCount=0; };
static int64_t monotonicUs(){
    static const int64_t frequency=[](){LARGE_INTEGER f;QueryPerformanceFrequency(&f);return f.QuadPart;}();
    LARGE_INTEGER now;QueryPerformanceCounter(&now);return (now.QuadPart/frequency)*1000000+(now.QuadPart%frequency)*1000000/frequency;
}
struct Sink {
    HANDLE ready=CreateEventW(nullptr,TRUE,FALSE,nullptr), wake=CreateEventW(nullptr,FALSE,FALSE,nullptr), thread=nullptr;
    std::atomic<bool> stopping{false},playing{false},reset{false},active{false},deviceChanged{false};
    std::wstring deviceId;
    std::atomic<int64_t> write{0},read{0},presented{0},underruns{0};
    std::atomic<int64_t> lastProducerUs{0},lastRenderUs{0},maxProducerGapUs{0},maxRenderGapUs{0},firstUnderrunUs{0},underrunProducerGapUs{0},underrunPadding{0};
    std::atomic<int64_t> endpointBufferFrames{0},endpointPaddingFrames{0};
    std::atomic<HRESULT> status{E_PENDING};int rate=48000,channels=2;static const int capacity=48000;
    std::vector<float> ring=std::vector<float>(capacity*2);std::mutex producer;
    PresentedSpan spans[128];int spanStart=0,spanEnd=0;int64_t deviceSubmitted=0;
    ~Sink(){stopping=true;SetEvent(wake);if(thread){WaitForSingleObject(thread,INFINITE);CloseHandle(thread);}CloseHandle(ready);CloseHandle(wake);}
};
class EndpointNotifications final:public IMMNotificationClient {
    std::atomic<ULONG> refs{1};Sink* sink;
    void invalidate(){sink->deviceChanged=true;SetEvent(sink->wake);}
public:
    explicit EndpointNotifications(Sink* value):sink(value){}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id==__uuidof(IUnknown)||id==__uuidof(IMMNotificationClient)){*out=(IMMNotificationClient*)this;AddRef();return S_OK;}return E_NOINTERFACE;}
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{auto n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow flow,ERole role,LPCWSTR) override{if(flow==eRender&&role==eMultimedia)invalidate();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR id) override{if(id&&sink->deviceId==id)invalidate();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR id,DWORD state) override{if(id&&sink->deviceId==id&&state!=DEVICE_STATE_ACTIVE)invalidate();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR) override{return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR,const PROPERTYKEY) override{return S_OK;}
};
static std::unordered_map<int64_t,std::shared_ptr<Sink>> sinks;
static std::shared_ptr<Sink> sink(JNIEnv* e,jlong id){std::lock_guard<std::mutex> l(handlesMutex);auto it=sinks.find(id);if(it==sinks.end()){fail(e,"Audio endpoint handle is closed");return {};}return it->second;}
static void updatePresentation(Sink* s,IAudioClock* clock){
        UINT64 ticks=0,qpc=0,freq=0;clock->GetFrequency(&freq);clock->GetPosition(&ticks,&qpc);if(freq){
            int64_t position=(int64_t)((double)ticks*48000/freq);
            while(s->spanStart!=s->spanEnd && s->spans[s->spanStart].deviceEnd<=position){
                s->presented=s->spans[s->spanStart].contentStart+s->spans[s->spanStart].contentCount;s->spanStart=(s->spanStart+1)%128;
            }
            if(s->spanStart!=s->spanEnd){auto span=s->spans[s->spanStart];s->presented=span.contentStart+std::max<int64_t>(0,std::min<int64_t>(span.contentCount,position-span.deviceStart));}
        }
}
static DWORD WINAPI render(void* arg){
    auto* s=(Sink*)arg;HRESULT hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED);bool com=SUCCEEDED(hr);IMMDeviceEnumerator* en=nullptr;IMMDevice* dev=nullptr;IAudioClient* client=nullptr;IAudioRenderClient* render=nullptr;IAudioClock* clock=nullptr;IAudioStreamVolume* streamVolume=nullptr;HANDLE task=nullptr;DWORD taskIndex=0;UINT32 size=0;EndpointNotifications* notification=nullptr;LPWSTR endpointId=nullptr;
    WAVEFORMATEX fmt={WAVE_FORMAT_IEEE_FLOAT,2,48000,48000*8,8,32,0};
    if(FAILED(hr))goto done;
    hr=CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&en);if(FAILED(hr))goto done;
    hr=en->GetDefaultAudioEndpoint(eRender,eMultimedia,&dev);if(FAILED(hr))goto done;
    hr=dev->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,(void**)&client);if(FAILED(hr))goto done;
    hr=client->Initialize(AUDCLNT_SHAREMODE_SHARED,AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM|AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,2500000,0,&fmt,nullptr);if(FAILED(hr))goto done;
    hr=client->GetBufferSize(&size);if(FAILED(hr))goto done;s->endpointBufferFrames=size;
    hr=client->GetService(__uuidof(IAudioRenderClient),(void**)&render);if(FAILED(hr))goto done;
    hr=client->GetService(__uuidof(IAudioClock),(void**)&clock);if(FAILED(hr))goto done;
    hr=client->GetService(__uuidof(IAudioStreamVolume),(void**)&streamVolume);if(FAILED(hr))goto done;
    {float gains[2]={0,0};streamVolume->SetAllVolumes(2,gains);}
    if(SUCCEEDED(dev->GetId(&endpointId))&&endpointId){s->deviceId=endpointId;CoTaskMemFree(endpointId);endpointId=nullptr;}
    notification=new EndpointNotifications(s);hr=en->RegisterEndpointNotificationCallback(notification);if(FAILED(hr))goto done;
    task=AvSetMmThreadCharacteristicsW(L"Audio",&taskIndex);if(!task){hr=HRESULT_FROM_WIN32(GetLastError());goto done;}
    if(!AvSetMmThreadPriority(task,AVRT_PRIORITY_NORMAL)){hr=HRESULT_FROM_WIN32(GetLastError());goto done;}
    s->status=S_OK;SetEvent(s->ready);
    {bool started=false;
    auto begin=[&]()->HRESULT{
        auto result=client->Start();if(FAILED(result))return result;
        started=true;s->active=true;
        for(int step=1;step<=5;step++){float g=step/5.f;float gains[2]={g,g};streamVolume->SetAllVolumes(2,gains);Sleep(1);}
        return S_OK;
    };
    while(!s->stopping){
        // Timer-driven shared mode permits an explicit resilience buffer. The producer still wakes us on writes.
        WaitForSingleObject(s->wake,s->playing?5:20);
        if(s->deviceChanged){hr=AUDCLNT_E_DEVICE_INVALIDATED;break;}
        if(s->reset.exchange(false)){client->Stop();client->Reset();started=false;s->read=0;s->write=0;s->presented=0;s->deviceSubmitted=0;s->spanStart=0;s->spanEnd=0;SetEvent(s->ready);}
        if(!s->playing&&started){for(int step=4;step>=0;step--){float g=step/5.f;float gains[2]={g,g};streamVolume->SetAllVolumes(2,gains);Sleep(1);}client->Stop();updatePresentation(s,clock);started=false;s->active=false;}
        if(!started&&!s->playing){s->lastRenderUs=0;continue;}
        {auto now=monotonicUs();auto previous=s->lastRenderUs.exchange(now);if(previous)s->maxRenderGapUs=std::max(s->maxRenderGapUs.load(),now-previous);}
        UINT32 padding=0;hr=client->GetCurrentPadding(&padding);if(FAILED(hr))break;s->endpointPaddingFrames=padding;UINT32 available=size-padding;
        auto read=s->read.load();auto count=(UINT32)std::min<int64_t>(available,s->write.load()-read);
        // Free endpoint space is not a playback deadline. Never insert silence while valid audio is queued.
        // Prime real PCM before Start, including short tracks, rather than starting an empty device buffer.
        if(!started||padding>0)available=count;
        if(!available){
            if(!started&&s->playing&&padding>0){hr=begin();if(FAILED(hr))break;}
            if(started)updatePresentation(s,clock);
            continue;
        }
        BYTE* dst=nullptr;hr=render->GetBuffer(available,&dst);if(FAILED(hr))break;
        for(UINT32 i=0;i<count;i++)for(int ch=0;ch<2;ch++)((float*)dst)[i*2+ch]=s->ring[((read+i)%Sink::capacity)*2+ch];
        if(count<available){memset((float*)dst+count*2,0,(available-count)*8);s->underruns+=available-count;
            if(!s->firstUnderrunUs.load()){auto now=monotonicUs();s->underrunPadding=padding;s->underrunProducerGapUs=s->lastProducerUs.load()?now-s->lastProducerUs.load():0;s->firstUnderrunUs=now;}}
        s->spans[s->spanEnd]={s->deviceSubmitted,s->deviceSubmitted+available,read,(int64_t)count};
        s->spanEnd=(s->spanEnd+1)%128;if(s->spanEnd==s->spanStart)s->spanStart=(s->spanStart+1)%128;
        s->deviceSubmitted+=available;
        s->read=read+count;hr=render->ReleaseBuffer(available,0);if(FAILED(hr))break;
        if(!started&&s->playing){hr=begin();if(FAILED(hr))break;}
        if(started)updatePresentation(s,clock);
    }}
 done:
    s->active=false;s->status=hr;SetEvent(s->ready);if(notification){if(en)en->UnregisterEndpointNotificationCallback(notification);notification->Release();}if(endpointId)CoTaskMemFree(endpointId);if(client)client->Stop();if(streamVolume)streamVolume->Release();if(clock)clock->Release();if(render)render->Release();if(client)client->Release();if(dev)dev->Release();if(en)en->Release();if(task)AvRevertMmThreadCharacteristics(task);if(com)CoUninitialize();return 0;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_sinkOpen(JNIEnv* e,jobject){auto s=std::make_shared<Sink>();s->thread=CreateThread(nullptr,0,render,s.get(),0,nullptr);if(!s->thread||WaitForSingleObject(s->ready,10000)!=WAIT_OBJECT_0||FAILED(s->status)){fail(e,"WASAPI audio endpoint could not open");return 0;}auto id=nextHandle++;{std::lock_guard<std::mutex> l(handlesMutex);sinks[id]=s;}return id;}
extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_sinkWrite(JNIEnv* e,jobject,jlong id,jobject buffer,jint frames){auto s=sink(e,id);if(!s)return -1;auto* data=(float*)e->GetDirectBufferAddress(buffer);if(!data||frames<0||e->GetDirectBufferCapacity(buffer)<(int64_t)frames*8){fail(e,"Invalid endpoint PCM buffer");return -1;}if(FAILED(s->status)){fail(e,"Audio endpoint was lost");return -1;}std::lock_guard<std::mutex> l(s->producer);auto now=monotonicUs();auto previous=s->lastProducerUs.exchange(now);if(previous&&s->playing)s->maxProducerGapUs=std::max(s->maxProducerGapUs.load(),now-previous);auto w=s->write.load();int count=(int)std::min<int64_t>(frames,Sink::capacity-(w-s->read.load()));for(int i=0;i<count;i++)for(int ch=0;ch<2;ch++)s->ring[((w+i)%Sink::capacity)*2+ch]=data[i*2+ch];s->write=w+count;SetEvent(s->wake);return count;}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_sinkPlaying(JNIEnv* e,jobject,jlong id,jboolean value){auto s=sink(e,id);if(s){s->playing=value;if(!value)s->lastProducerUs=0;SetEvent(s->wake);ULONGLONG until=GetTickCount64()+5000;
    while(s->active.load()!=(bool)value && SUCCEEDED(s->status) && GetTickCount64()<until)Sleep(1);
    if(FAILED(s->status)||s->active.load()!=(bool)value)fail(e,"Audio endpoint state change failed");}}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_sinkReset(JNIEnv* e,jobject,jlong id){auto s=sink(e,id);if(s){ResetEvent(s->ready);s->reset=true;SetEvent(s->wake);if(WaitForSingleObject(s->ready,5000)!=WAIT_OBJECT_0)fail(e,"Audio endpoint reset timed out");}}
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_sinkPosition(JNIEnv* e,jobject,jlong id){auto s=sink(e,id);if(s&&(FAILED(s->status)||s->deviceChanged)){fail(e,"Audio endpoint was lost or changed");return 0;}return s?s->presented.load():0;}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_musicglass_desktop_NativeAudio_sinkTelemetry(JNIEnv* e,jobject,jlong id){
    auto s=sink(e,id);if(!s)return nullptr;
    jlong data[8]={s->maxProducerGapUs.load(),s->maxRenderGapUs.load(),s->firstUnderrunUs.load(),s->underrunProducerGapUs.load(),s->underrunPadding.load(),s->endpointBufferFrames.load(),s->endpointPaddingFrames.load(),s->write.load()-s->read.load()};
    auto result=e->NewLongArray(8);if(result)e->SetLongArrayRegion(result,0,8,data);return result;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_sinkUnderruns(JNIEnv* e,jobject,jlong id){auto s=sink(e,id);return s?s->underruns.load():0;}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_sinkClose(JNIEnv*,jobject,jlong id){std::lock_guard<std::mutex> l(handlesMutex);sinks.erase(id);}// Diagnostic capture uses the same Windows endpoint's loopback, not the renderer's input buffer.
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_captureLoopback(JNIEnv* e,jobject,jobject buffer,jint milliseconds){
    float* output=(float*)e->GetDirectBufferAddress(buffer);jlong capacity=e->GetDirectBufferCapacity(buffer)/8;
    if(!output||milliseconds<100||milliseconds>30000||capacity<192000LL*milliseconds/1000){fail(e,"Invalid loopback capture bounds");return 0;}
    HRESULT hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED);bool com=SUCCEEDED(hr);
    IMMDeviceEnumerator* enumerator=nullptr;IMMDevice* device=nullptr;IAudioClient* client=nullptr;IAudioCaptureClient* capture=nullptr;WAVEFORMATEX* format=nullptr;
    int64_t frames=0;int rate=0;bool floating=false;
    if(FAILED(hr))goto cleanup;
    hr=CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&enumerator);if(FAILED(hr))goto cleanup;
    hr=enumerator->GetDefaultAudioEndpoint(eRender,eMultimedia,&device);if(FAILED(hr))goto cleanup;
    hr=device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,(void**)&client);if(FAILED(hr))goto cleanup;
    hr=client->GetMixFormat(&format);if(FAILED(hr))goto cleanup;rate=format->nSamplesPerSec;
    floating=format->wFormatTag==WAVE_FORMAT_IEEE_FLOAT || (format->wFormatTag==WAVE_FORMAT_EXTENSIBLE && ((WAVEFORMATEXTENSIBLE*)format)->SubFormat.Data1==WAVE_FORMAT_IEEE_FLOAT);
    if((floating&&format->wBitsPerSample!=32)||(!floating&&format->wBitsPerSample!=16&&format->wBitsPerSample!=24&&format->wBitsPerSample!=32)||format->nChannels<1){hr=E_NOTIMPL;goto cleanup;}
    hr=client->Initialize(AUDCLNT_SHAREMODE_SHARED,AUDCLNT_STREAMFLAGS_LOOPBACK,1000000,0,format,nullptr);if(FAILED(hr))goto cleanup;
    hr=client->GetService(__uuidof(IAudioCaptureClient),(void**)&capture);if(FAILED(hr))goto cleanup;
    hr=client->Start();if(FAILED(hr))goto cleanup;
    {ULONGLONG deadline=GetTickCount64()+milliseconds;while(GetTickCount64()<deadline){
        UINT32 count=0;hr=capture->GetNextPacketSize(&count);if(FAILED(hr))break;if(!count){Sleep(2);continue;}
        BYTE* bytes=nullptr;DWORD flags=0;UINT64 pos=0,qpc=0;hr=capture->GetBuffer(&bytes,&count,&flags,&pos,&qpc);if(FAILED(hr))break;
        for(UINT32 i=0;i<count&&frames<capacity;i++,frames++)for(int ch=0;ch<2;ch++){
            float value=0;int channel=std::min<int>(ch,format->nChannels-1);const BYTE* sample=bytes?bytes+i*format->nBlockAlign+channel*(format->wBitsPerSample/8):nullptr;
            if(!(flags&AUDCLNT_BUFFERFLAGS_SILENT)&&sample){if(floating)memcpy(&value,sample,4);else if(format->wBitsPerSample==16){int16_t n;memcpy(&n,sample,2);value=n/32768.f;}else if(format->wBitsPerSample==24){int32_t n=(int32_t)((uint32_t)sample[0]<<8|(uint32_t)sample[1]<<16|(uint32_t)sample[2]<<24);value=n/2147483648.f;}else{int32_t n;memcpy(&n,sample,4);value=n/2147483648.f;}}
            output[frames*2+ch]=value;
        }
        hr=capture->ReleaseBuffer(count);if(FAILED(hr))break;
    }}
cleanup:
    if(client)client->Stop();if(capture)capture->Release();if(format)CoTaskMemFree(format);if(client)client->Release();if(device)device->Release();if(enumerator)enumerator->Release();if(com)CoUninitialize();
    if(FAILED(hr)){fail(e,"Windows loopback capture failed");return 0;}return ((jlong)rate<<32)|(frames&0xffffffffLL);
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_musicglass_desktop_NativeAudio_decoderTags(JNIEnv* e,jobject,jlong id){
    auto d=decoder(e,id);if(!d)return nullptr;jobjectArray result=e->NewObjectArray(3,e->FindClass("java/lang/String"),nullptr);
    const char* keys[]={"title","artist","album"};for(int i=0;i<3;i++){
        AVDictionaryEntry* entry=av_dict_get(d->format->metadata,keys[i],nullptr,0);
        if(!entry)entry=av_dict_get(d->format->streams[d->stream]->metadata,keys[i],nullptr,0);
        if(entry){int size=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,entry->value,-1,nullptr,0);
            if(size>0&&size<16384){std::wstring text(size,L'\0');MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,entry->value,-1,text.data(),size);
                jstring value=e->NewString((const jchar*)text.data(),size-1);e->SetObjectArrayElement(result,i,value);e->DeleteLocalRef(value);}}
    }return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_musicglass_desktop_NativeAudio_decoderCodecName(JNIEnv* e,jobject,jlong id){auto d=decoder(e,id);return d?e->NewStringUTF(avcodec_get_name(d->codec->codec_id)):nullptr;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_musicglass_desktop_NativeAudio_decoderArtwork(JNIEnv* e,jobject,jlong id){
    auto d=decoder(e,id);if(!d)return nullptr;
    for(unsigned i=0;i<d->format->nb_streams;i++){
        AVStream* stream=d->format->streams[i];if(!(stream->disposition&AV_DISPOSITION_ATTACHED_PIC))continue;
        AVPacket* packet=&stream->attached_pic;
        if(packet->size<=0||packet->size>16*1024*1024)return nullptr;
        jbyteArray result=e->NewByteArray(packet->size);if(result)e->SetByteArrayRegion(result,0,packet->size,(const jbyte*)packet->data);return result;
    }return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_musicglass_desktop_NativeAudio_decoderProfile(JNIEnv* e,jobject,jlong id){auto d=decoder(e,id);if(!d)return nullptr;const char* value=avcodec_profile_name(d->codec->codec_id,d->codec->profile);return e->NewStringUTF(value?value:"unspecified");}
extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_decoderSourceRate(JNIEnv* e,jobject,jlong id){auto d=decoder(e,id);return d?d->codec->sample_rate:0;}
extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_decoderSourceChannels(JNIEnv* e,jobject,jlong id){auto d=decoder(e,id);return d?d->codec->ch_layout.nb_channels:0;}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_musicglass_desktop_NativeAudio_handleCounts(JNIEnv* e,jobject){std::lock_guard<std::mutex> l(handlesMutex);jlong counts[2]={(jlong)decoders.size(),(jlong)sinks.size()};auto result=e->NewLongArray(2);e->SetLongArrayRegion(result,0,2,counts);return result;}

extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_processHandleCount(JNIEnv*,jobject){DWORD count=0;return GetProcessHandleCount(GetCurrentProcess(),&count)?(jint)count:-1;}

// MMCSS registration belongs to the invoking producer thread, independently of the WASAPI render thread.
static std::mutex schedulingMutex;
struct AudioSchedulingTask { HANDLE task; DWORD thread; };
static std::unordered_map<int64_t,AudioSchedulingTask> schedulingTasks;
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_audioThreadEnter(JNIEnv* e,jobject){
    DWORD index=0;HANDLE task=AvSetMmThreadCharacteristicsW(L"Audio",&index);
    if(!task){fail(e,"Windows audio scheduling registration failed");return 0;}
    if(!AvSetMmThreadPriority(task,AVRT_PRIORITY_NORMAL)){AvRevertMmThreadCharacteristics(task);fail(e,"Windows audio scheduling priority failed");return 0;}
    auto id=nextHandle++;std::lock_guard<std::mutex> lock(schedulingMutex);schedulingTasks[id]={task,GetCurrentThreadId()};return id;
}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_audioThreadLeave(JNIEnv* e,jobject,jlong id){
    std::lock_guard<std::mutex> lock(schedulingMutex);auto task=schedulingTasks.find(id);
    if(task==schedulingTasks.end()){fail(e,"Audio scheduling registration is closed");return;}
    if(task->second.thread!=GetCurrentThreadId()){fail(e,"Audio scheduling must be released by its owning thread");return;}
    if(!AvRevertMmThreadCharacteristics(task->second.task)){fail(e,"Windows audio scheduling release failed");return;}
    schedulingTasks.erase(task);
}
extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_audioThreadRegistrationCount(JNIEnv*,jobject){
    std::lock_guard<std::mutex> lock(schedulingMutex);return (jint)schedulingTasks.size();
}


// SoundTouch is instantiated only for non-1x tempo. Default playback remains bit-exact decode PCM.
struct Tempo { soundtouch::SoundTouch engine;std::mutex mutex; };
static std::unordered_map<int64_t,std::shared_ptr<Tempo>> tempos;
static std::shared_ptr<Tempo> tempo(JNIEnv* e,jlong id){std::lock_guard<std::mutex> lock(handlesMutex);auto it=tempos.find(id);if(it==tempos.end()){fail(e,"Tempo handle is closed");return {};}return it->second;}
extern "C" JNIEXPORT jlong JNICALL Java_com_musicglass_desktop_NativeAudio_tempoOpen(JNIEnv* e,jobject,jfloat speed){
    if(!std::isfinite(speed)||speed<.5f||speed>2.f){fail(e,"Invalid tempo rate");return 0;}
    try{auto t=std::make_shared<Tempo>();t->engine.setSampleRate(48000);t->engine.setChannels(2);t->engine.setSetting(SETTING_USE_QUICKSEEK,0);t->engine.setTempo(speed);
        auto id=nextHandle++;std::lock_guard<std::mutex> lock(handlesMutex);tempos[id]=t;return id;
    }catch(const std::exception&){fail(e,"Pitch-preserving engine initialization failed");return 0;}
}
static float* tempoBuffer(JNIEnv* e,jobject buffer,jint frames){auto* data=(float*)e->GetDirectBufferAddress(buffer);if(!data||frames<1||frames>4096||e->GetDirectBufferCapacity(buffer)<(int64_t)frames*8){fail(e,"Invalid tempo PCM buffer");return nullptr;}return data;}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_tempoPut(JNIEnv* e,jobject,jlong id,jobject buffer,jint frames){
    auto t=tempo(e,id);if(!t)return;auto* data=tempoBuffer(e,buffer,frames);if(!data)return;
    for(int i=0;i<frames*2;i++)if(!std::isfinite(data[i])||std::abs(data[i])>1000000.f){fail(e,"Corrupt audio PCM supplied to tempo engine");return;}
    try{std::lock_guard<std::mutex> lock(t->mutex);t->engine.putSamples(data,frames);}catch(const std::exception&){fail(e,"Pitch-preserving PCM input failed");}
}
extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_tempoRead(JNIEnv* e,jobject,jlong id,jobject buffer,jint frames){
    auto t=tempo(e,id);if(!t)return 0;auto* data=tempoBuffer(e,buffer,frames);if(!data)return 0;
    try{std::lock_guard<std::mutex> lock(t->mutex);return t->engine.receiveSamples(data,frames);}catch(const std::exception&){fail(e,"Pitch-preserving PCM output failed");return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_tempoFlush(JNIEnv* e,jobject,jlong id){auto t=tempo(e,id);if(t)try{std::lock_guard<std::mutex> lock(t->mutex);t->engine.flush();}catch(const std::exception&){fail(e,"Pitch-preserving flush failed");}}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_tempoClear(JNIEnv* e,jobject,jlong id){auto t=tempo(e,id);if(t){std::lock_guard<std::mutex> lock(t->mutex);t->engine.clear();}}
extern "C" JNIEXPORT void JNICALL Java_com_musicglass_desktop_NativeAudio_tempoClose(JNIEnv*,jobject,jlong id){std::lock_guard<std::mutex> lock(handlesMutex);tempos.erase(id);}
extern "C" JNIEXPORT jint JNICALL Java_com_musicglass_desktop_NativeAudio_tempoHandleCount(JNIEnv*,jobject){std::lock_guard<std::mutex> lock(handlesMutex);return (jint)tempos.size();}
extern "C" JNIEXPORT jstring JNICALL Java_com_musicglass_desktop_NativeAudio_tempoVersion(JNIEnv* e,jobject){return e->NewStringUTF(soundtouch::SoundTouch::getVersionString());}
