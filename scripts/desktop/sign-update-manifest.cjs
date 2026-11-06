#!/usr/bin/env node
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
class Failure extends Error {constructor(code){super(code);this.code=code;}}
const reject=code=>{throw new Failure(code);};
function bounded(file,max,code,sizeCode=code){
  let fd;
  try {fd=fs.openSync(file,'r');if(!fs.fstatSync(fd).isFile())reject(code);
    const data=Buffer.alloc(max+1);let size=0;
    while(size<data.length){const n=fs.readSync(fd,data,size,data.length-size,null);if(!n)break;size+=n;}
    if(size>max)reject(sizeCode);return data.subarray(0,size);
  }catch(error){if(error instanceof Failure)throw error;reject(code);}finally{if(fd!==undefined)fs.closeSync(fd);}
}
function options(argv){
  const allowed=new Set(['--payload','--key-id','--private-key','--output','--java','--validator-app','--public-key']),out={};
  for(let i=0;i<argv.length;i+=2){if(!allowed.has(argv[i])||!argv[i+1]||out[argv[i]]!==undefined)reject('USAGE');out[argv[i]]=argv[i+1];}
  for(const flag of ['--payload','--key-id','--private-key','--output','--java','--validator-app'])if(!out[flag])reject('USAGE');
  if(!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(out['--key-id']))reject('KEY_ID');return out;
}
function validate(payload,opts){
  const java=path.resolve(opts['--java']),app=path.resolve(opts['--validator-app'],'app');
  if(!fs.existsSync(java)||!fs.existsSync(app))reject('VALIDATOR_ERROR');
  const result=spawnSync(java,['-cp',path.join(app,'*'),'com.musicglass.desktop.UpdateManifestValidationMain'],
    {input:payload,encoding:'utf8',timeout:15000,maxBuffer:4096,windowsHide:true,shell:false});
  const output=(result.stdout||'').trim(),digest=crypto.createHash('sha256').update(payload).digest('hex');
  if(result.status===0&&!result.error&&output===`RIFF_UPDATE_SYNTAX V1 VALID ${digest}`)return;
  const code=/^RIFF_UPDATE_SYNTAX V1 (INVALID_PAYLOAD|SIZE_LIMIT|UNSUPPORTED_FORMAT)$/.exec(output);
  if(result.status===2&&code)reject(code[1]);reject('VALIDATOR_ERROR');
}
function preimage(payload,id){
  // Independent implementation of contract section 5, not the Kotlin preimage assembler.
  const key=Buffer.from(id,'ascii'),alg=Buffer.from('Ed25519','ascii');
  const u16=n=>{const b=Buffer.alloc(2);b.writeUInt16BE(n);return b;};
  const u32=n=>{const b=Buffer.alloc(4);b.writeUInt32BE(n);return b;};
  return Buffer.concat([Buffer.from('RIFF-DESKTOP-UPDATE\0','ascii'),u32(1),u16(key.length),key,u16(alg.length),alg,u32(payload.length),payload]);
}
function publish(output,bytes){
  const destination=path.resolve(output),temporary=path.join(path.dirname(destination),`.riff-update-${crypto.randomUUID()}.tmp`);let fd;
  try {fd=fs.openSync(temporary,'wx',0o600);fs.writeFileSync(fd,bytes);fs.fsyncSync(fd);fs.closeSync(fd);fd=undefined;
    // Same-volume atomic no-clobber hard-link publication; no copy/delete or overwrite fallback.
    fs.linkSync(temporary,destination);
  }catch(error){reject(error.code==='EEXIST'?'OUTPUT_EXISTS':'OUTPUT_ERROR');}
  finally{if(fd!==undefined)fs.closeSync(fd);try{fs.unlinkSync(temporary);}catch(error){if(error.code!=='ENOENT')reject('OUTPUT_ERROR');}}
}
function run(argv){
  const opts=options(argv),destination=path.resolve(opts['--output']);
  if(fs.existsSync(destination))reject('OUTPUT_EXISTS');
  const payload=bounded(opts['--payload'],65536,'PAYLOAD_ERROR','SIZE_LIMIT');if(!payload.length)reject('INVALID_PAYLOAD');
  validate(payload,opts); // Shared schema check precedes private-key access and actual signing.
  let raw;
  try {raw=bounded(opts['--private-key'],8192,'KEY_ERROR');let key;
    try{key=crypto.createPrivateKey({key:raw,format:'der',type:'pkcs8'});}catch(_){reject('KEY_ERROR');}
    if(key.asymmetricKeyType!=='ed25519'||!key.export({format:'der',type:'pkcs8'}).equals(raw))reject('KEY_ERROR');
    const publicKey=crypto.createPublicKey(key),der=publicKey.export({format:'der',type:'spki'});
    if(opts['--public-key']&&!bounded(opts['--public-key'],44,'KEY_MISMATCH').equals(der))reject('KEY_MISMATCH');
    const message=preimage(payload,opts['--key-id']),signature=crypto.sign(null,message,key);
    if(signature.length!==64||!crypto.verify(null,message,publicKey,signature))reject('VERIFY_ERROR');
    const envelope=Buffer.from(JSON.stringify({envelopeVersion:1,keyId:opts['--key-id'],algorithm:'Ed25519',payloadBase64:payload.toString('base64'),signatureBase64:signature.toString('base64')})+'\n');
    if(envelope.length>131072)reject('SIZE_LIMIT');publish(destination,envelope);
  }finally{if(raw)raw.fill(0);} // Best effort only; OpenSSL/GC copies are not guaranteed erased.
}
if(require.main===module){try{run(process.argv.slice(2));console.log('RIFF_SIGN_UPDATE OK');}
  catch(error){const code=error instanceof Failure?error.code:'INTERNAL_ERROR';console.error(`RIFF_SIGN_UPDATE ${code}`);
    process.exitCode=({USAGE:2,KEY_ID:2,INVALID_PAYLOAD:3,SIZE_LIMIT:3,UNSUPPORTED_FORMAT:3,PAYLOAD_ERROR:3,KEY_ERROR:4,KEY_MISMATCH:4,VERIFY_ERROR:4,OUTPUT_EXISTS:5,OUTPUT_ERROR:5,VALIDATOR_ERROR:6})[code]||7;}}
module.exports={run,preimage};