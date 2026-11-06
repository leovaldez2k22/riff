package com.musicglass.desktop

import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import com.musicglass.data.Scopes
import com.musicglass.data.SqliteLibraryRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Secrets are bound to this Windows user AND an explicit scope/kind entropy domain. */
internal class DpapiCredentialVault(private val directory:File) {
    @Structure.FieldOrder("size","data")
    class Blob:Structure(){@JvmField var size:Int=0;@JvmField var data:Pointer?=null}
    private interface Crypt:StdCallLibrary {
        fun CryptProtectData(input:Blob,description:WString?,entropy:Blob?,reserved:Pointer?,prompt:Pointer?,flags:Int,output:Blob):Boolean
        fun CryptUnprotectData(input:Blob,description:Pointer?,entropy:Blob?,reserved:Pointer?,prompt:Pointer?,flags:Int,output:Blob):Boolean
    }
    private interface Kernel:StdCallLibrary {fun LocalFree(pointer:Pointer):Pointer?}
    private val crypt=Native.load("crypt32",Crypt::class.java)
    private val kernel=Native.load("kernel32",Kernel::class.java)
    init{check(directory.mkdirs()||directory.isDirectory)}
    private fun file(scope:String,kind:String):File {Scopes.validate(scope);require(kind.matches(Regex("[a-z_]{1,40}")));return File(directory,SqliteLibraryRepository.digest("$scope:$kind")+".dpapi")}
    @Synchronized fun store(scope:String,kind:String,secret:ByteArray){
        require(secret.size in 1..65536)
        val encrypted=transform(scope,kind,secret,true)
        val target=file(scope,kind);val temporary=File(directory,".${target.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            java.nio.channels.FileChannel.open(temporary.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE).use{channel->val buffer=java.nio.ByteBuffer.wrap(encrypted);while(buffer.hasRemaining())channel.write(buffer);channel.force(true)}
            Files.move(temporary.toPath(),target.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        }finally{encrypted.fill(0);temporary.delete()}
    }
    @Synchronized fun load(scope:String,kind:String):ByteArray? {
        val file=file(scope,kind);if(!file.isFile)return null
        require(file.length() in 1..1048576){"Credential envelope is invalid"}
        val encrypted=file.readBytes();try{return transform(scope,kind,encrypted,false)}finally{encrypted.fill(0)}
    }
    @Synchronized fun delete(scope:String,kind:String){val file=file(scope,kind);check(!file.exists()||file.delete()){"Cannot delete credential"}}
    private fun transform(scope:String,kind:String,input:ByteArray,protect:Boolean):ByteArray {
        file(scope,kind)
        val memory=Memory(input.size.toLong());memory.write(0,input,0,input.size)
        val entropyBytes=java.security.MessageDigest.getInstance("SHA-256").digest("riff-credential:v1:$scope:$kind".toByteArray())
        val entropyMemory=Memory(entropyBytes.size.toLong());entropyMemory.write(0,entropyBytes,0,entropyBytes.size)
        val inBlob=Blob().apply{size=input.size;data=memory;write()};val entropy=Blob().apply{size=entropyBytes.size;data=entropyMemory;write()};val output=Blob()
        try {
            val success=if(protect)crypt.CryptProtectData(inBlob,WString("Riff credential"),entropy,null,null,1,output)else crypt.CryptUnprotectData(inBlob,null,entropy,null,null,1,output)
            check(success){"Windows could not decrypt this user's credential; sign in again"};output.read()
            require(output.size in 1..1048576)
            return checkNotNull(output.data).getByteArray(0,output.size)
        }finally{output.data?.let{pointer->if(output.size>0)pointer.setMemory(0,output.size.toLong(),0.toByte());kernel.LocalFree(pointer)};memory.clear();memory.close();entropyMemory.clear();entropyMemory.close();entropyBytes.fill(0)}
    }
}

internal data class AccountToken(val scope:String,val generation:Long,val nonce:String)
internal class AccountSessionCoordinator {
    private val nonce=java.util.UUID.randomUUID().toString()
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(AccountToken(Scopes.GUEST,0,nonce))
    val token:StateFlow<AccountToken> = mutable
    fun isCurrent(expected:AccountToken)=mutable.value==expected
    fun scopeFor(channelId:String):String {
        require(channelId.matches(Regex("UC[A-Za-z0-9_-]{20,30}"))){"Provider did not verify a stable channel identity"}
        return "account_"+SqliteLibraryRepository.digest("riff-account-scope:v2:$channelId")
    }
    suspend fun transitionVerified(channelId:String):AccountToken=mutex.withLock {
        require(channelId.matches(Regex("UC[A-Za-z0-9_-]{20,30}"))){"Provider did not verify a stable channel identity"}
        AccountToken(scopeFor(channelId),mutable.value.generation+1,nonce).also{mutable.value=it}
    }
    suspend fun signOut():AccountToken=mutex.withLock{AccountToken(Scopes.GUEST,mutable.value.generation+1,nonce).also{mutable.value=it}}
}
