package com.loopin.player2

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.loopin.player2.core.cache.MediaSource
import com.loopin.player2.core.operations.OperationalUpdateManager
import com.loopin.player2.core.operations.OperationalUpdateState
import com.loopin.player2.core.operations.UpdateChannel
import com.loopin.player2.core.sync.*
import org.json.JSONObject
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class AuthenticatedPlayerUpdateSource(private val endpoint:String,private val credential:()->String?,private val installedVersionCode:Long,private val authoritativeChannel:(String)->Unit={}):PlayerUpdateSource,UpdateMediaSourceFactory {
    override fun latest(releaseChannel:String):PlayerUpdateSourceResult {
        val secret=credential()?:return PlayerUpdateSourceResult.Failed("Missing credential",false)
        val response=post(secret,JSONObject().put("action","check").put("version_code",installedVersionCode).put("version_name",BuildConfig.VERSION_NAME).put("channel",releaseChannel))
        if(response.first==204)return PlayerUpdateSourceResult.Available(noUpdate(installedVersionCode))
        if(response.first==401)return PlayerUpdateSourceResult.Failed("Authentication failed",false)
        if(response.first !in 200..299||response.second==null)return if(response.first==null)PlayerUpdateSourceResult.Offline("Network unavailable") else PlayerUpdateSourceResult.Failed("Update HTTP ${response.first}",response.first==408||response.first==429||(response.first?:0)>=500)
        return runCatching { val o=JSONObject(response.second!!);authoritativeChannel(o.getString("channel"));PlayerUpdateSourceResult.Available(PlayerUpdateInfo(o.getString("release_id"),o.getLong("version_code"),o.getString("version_name"),endpoint,o.getLong("size"),o.getString("sha256"),o.getString("package_name"),o.getString("certificate_sha256"),o.getString("channel"),o.optString("release_notes").takeIf(String::isNotBlank))) }.getOrElse{PlayerUpdateSourceResult.Failed("Invalid update metadata",false)}
    }
    override fun sourceFor(info:PlayerUpdateInfo)=MediaSource { val secret=credential()?:error("Missing credential");val response=post(secret,JSONObject().put("action","download").put("release_id",info.releaseId));if(response.first !in 200..299)error("Download authorization failed");val url=JSONObject(response.second!!).getString("download_url");val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=10_000;c.readTimeout=30_000;if(c.responseCode !in 200..299){c.disconnect();error("APK download failed")};object:FilterInputStream(c.inputStream){override fun close(){super.close();c.disconnect()}} }
    private fun post(secret:String,body:JSONObject):Pair<Int?,String?> { val c=URL(endpoint).openConnection() as HttpURLConnection;return try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=10_000;c.readTimeout=20_000;c.setRequestProperty("Authorization","Bearer $secret");c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())};val status=c.responseCode;status to (if(status==204)null else (if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()})}catch(_:Exception){null to null}finally{c.disconnect()} }
    private fun noUpdate(v:Long)=PlayerUpdateInfo("00000000-0000-0000-0000-000000000000",v,"current",endpoint,1,"0".repeat(64),"com.loopin.player2","0".repeat(64),"STABLE")
}

class UpdateCoordinator(private val manager:PlayerUpdateManager,private val state:OperationalUpdateManager,private val currentCode:Long){private val running=AtomicBoolean();fun run():String{if(!running.compareAndSet(false,true))return "ALREADY_CHECKING";return try{state.update(OperationalUpdateState.CHECKING);when(val check=manager.check(currentCode,state.snapshot().channel.name)){is UpdateCheckResult.UpToDate->{state.update(OperationalUpdateState.UP_TO_DATE);"UP_TO_DATE"};is UpdateCheckResult.Available->{state.update(OperationalUpdateState.UPDATE_AVAILABLE,check.info.versionName,versionCode=check.info.versionCode);state.update(OperationalUpdateState.DOWNLOADING,versionCode=check.info.versionCode);when(manager.prepare(check.info)){is ApkPreparationResult.Ready->{state.update(OperationalUpdateState.READY_TO_INSTALL,check.info.versionName,versionCode=check.info.versionCode);"UPDATE_AVAILABLE"};is ApkPreparationResult.Rejected->{state.update(OperationalUpdateState.FAILED,error="validation_failed",versionCode=check.info.versionCode);"DOWNLOAD_STARTED"}}};is UpdateCheckResult.Offline->{state.update(OperationalUpdateState.FAILED,error="offline");"UP_TO_DATE"};is UpdateCheckResult.Failed->{state.update(OperationalUpdateState.FAILED,error=check.reason);"UP_TO_DATE"}}}finally{running.set(false)}}}

class DeviceUpdateScheduler(private val context:Context){fun schedule(delay:Long=0)=((context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(JobInfo.Builder(0x4C50_0013,ComponentName(context,DeviceUpdateJobService::class.java)).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).setOverrideDeadline(delay+60_000).build())==JobScheduler.RESULT_SUCCESS)}
class DeviceUpdateJobService:JobService(){private var worker:Thread?=null;override fun onStartJob(p:JobParameters):Boolean{val c=(application as LoopinApplication).container;worker=Thread({c.updateCoordinator.run();jobFinished(p,false);Handler(Looper.getMainLooper()).post{c.updateScheduler.schedule(6*60*60*1000L)}},"loopin-update").apply{start()};return true};override fun onStopJob(p:JobParameters):Boolean{worker?.interrupt();return false}}
