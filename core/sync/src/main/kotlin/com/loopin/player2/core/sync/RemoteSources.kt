package com.loopin.player2.core.sync

import com.loopin.player2.core.cache.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

sealed interface RemoteManifestResult {
 data class Available(val manifest: VersionedManifest,val manifestSha256:String):RemoteManifestResult
 data object Unchanged:RemoteManifestResult; data object NoAssignment:RemoteManifestResult
 data object AuthenticationFailed:RemoteManifestResult
 data class Offline(val reason:String):RemoteManifestResult
 data class Failed(val reason:String,val retryable:Boolean):RemoteManifestResult
}
fun interface RemoteManifestSource { fun fetch(active:PublishedVersionRef?):RemoteManifestResult }
fun interface LocalManifestSource { fun active():PublishedVersionRef? }
fun interface RemoteMediaSourceFactory { fun sourceFor(item:NormalMediaContent):MediaSource? }
class CancellationSignal {
 private val cancelled=AtomicBoolean(); private val callbacks=CopyOnWriteArrayList<()->Unit>()
 fun cancel(){if(cancelled.compareAndSet(false,true))callbacks.forEach{runCatching(it)}}
 fun throwIfCancelled(){if(cancelled.get()||Thread.currentThread().isInterrupted)throw IOException("HTTP request cancelled")}
 fun onCancel(callback:()->Unit):AutoCloseable{callbacks+=callback;if(cancelled.get())callback();return AutoCloseable{callbacks-=callback}}
}
class AuthenticatedRemoteManifestSource(private val endpoint:String,private val credential:()->String?,private val cancellation:CancellationSignal=CancellationSignal()):RemoteManifestSource{
 override fun fetch(active:PublishedVersionRef?):RemoteManifestResult{
  val secret=credential()?.takeIf(String::isNotBlank)?:return RemoteManifestResult.AuthenticationFailed
  if(!isHttpUrl(endpoint))return RemoteManifestResult.Failed("Invalid manifest endpoint",false)
  var c:HttpURLConnection?=null;var registration:AutoCloseable?=null
  return try{c=(URL(endpoint).openConnection() as HttpURLConnection).apply{connectTimeout=10000;readTimeout=20000;setRequestProperty("Accept","application/json");setRequestProperty("Authorization","Bearer $secret");active?.manifestSha256?.let{setRequestProperty("If-None-Match","\"$it\"")}}
   registration=cancellation.onCancel(c::disconnect)
   when(val status=c.responseCode){204->RemoteManifestResult.NoAssignment;304->RemoteManifestResult.Unchanged;401->RemoteManifestResult.AuthenticationFailed;409->RemoteManifestResult.Failed("Manifest assignment conflict",false)
    in 200..299->{val etag=c.getHeaderField("ETag")?.trim()?.removeSurrounding("\"");if(etag==null||!SHA.matches(etag))RemoteManifestResult.Failed("Manifest response has invalid ETag",false)else RemoteManifestResult.Available(VersionedManifestCodec.decode(readBounded(c,1048576)),etag.lowercase())}
    else->RemoteManifestResult.Failed("Manifest HTTP $status",status==408||status==429||status>=500)}
  }catch(e:IllegalArgumentException){RemoteManifestResult.Failed(e.message?:"Invalid manifest",false)}catch(e:IOException){RemoteManifestResult.Offline(e.message?:"Network unavailable")}finally{registration?.close();c?.disconnect()}
 }
 private companion object{val SHA=Regex("[A-Fa-f0-9]{64}")}
}
class AuthenticatedRemoteMediaSourceFactory(private val endpoint:String,private val credential:()->String?,private val cancellation:CancellationSignal=CancellationSignal()):RemoteMediaSourceFactory{
 override fun sourceFor(item:NormalMediaContent):MediaSource?{val secret=credential()?.takeIf(String::isNotBlank)?:return null;return MediaSource{
  val c=(URL(endpoint).openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=10000;readTimeout=20000;setRequestProperty("Content-Type","application/json");setRequestProperty("Authorization","Bearer $secret")}
  try{c.outputStream.use{it.write("{\"asset_id\":\"${item.assetId}\"}".toByteArray())};if(c.responseCode !in 200..299)throw IOException("Media authorization HTTP ${c.responseCode}");val u=Json.parseToJsonElement(readBounded(c,16384)).jsonObject["download_url"]?.jsonPrimitive?.content?:throw IOException("Media authorization response invalid");HttpMediaSource(u,cancellation).open()}finally{c.disconnect()}
 }}
}
private class HttpMediaSource(private val url:String,private val cancellation:CancellationSignal):MediaSource{
 override fun open():InputStream{cancellation.throwIfCancelled();val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=10000;readTimeout=30000;instanceFollowRedirects=true};val r=cancellation.onCancel(c::disconnect)
  try{if(c.responseCode !in 200..299)throw IOException("Media download failed");return object:FilterInputStream(c.inputStream){override fun read()=super.read().also{cancellation.throwIfCancelled()};override fun read(b:ByteArray,o:Int,l:Int)=super.read(b,o,l).also{cancellation.throwIfCancelled()};override fun close(){runCatching{super.close()};r.close();c.disconnect()}}}catch(e:Exception){r.close();c.disconnect();throw e}}
}
private fun readBounded(c:HttpURLConnection,limit:Int)=c.inputStream.use{input->val out=ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;if(out.size()+n>limit)throw IOException("Response exceeds size limit");out.write(b,0,n)};out.toString(Charsets.UTF_8.name())}
internal fun isHttpUrl(v:String)=runCatching{URL(v).protocol.lowercase() in setOf("http","https")}.getOrDefault(false)
