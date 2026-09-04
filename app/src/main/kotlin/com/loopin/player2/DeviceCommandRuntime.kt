package com.loopin.player2

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.operations.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DeviceCommandHttpApi : DeviceCommandTransport {
    override fun fetch(request: DeviceCommandRequest): CommandFetchResult {
        val response = post(request, JSONObject().put("action", "fetch"))
        if (response.status == 401) return CommandFetchResult.Unauthorized
        if (response.status !in 200..299 || response.body == null) return CommandFetchResult.Failed(response.retryable)
        return runCatching {
            val array = JSONObject(response.body).getJSONArray("commands")
            require(array.length() <= 5)
            CommandFetchResult.Success((0 until array.length()).map { index -> array.getJSONObject(index).let { value ->
                RemoteCommand(value.getString("id"), CommandType.parse(value.getString("type")), parseTime(value.getString("created_at")), parseTime(value.getString("expires_at")))
            } })
        }.getOrElse { CommandFetchResult.Failed(false) }
    }
    override fun complete(request: DeviceCommandRequest, commandId: String, outcome: CommandOutcome): CommandCompletionResult {
        val result = JSONObject(); outcome.result.forEach { (key,value) -> result.put(key,value ?: JSONObject.NULL) }
        val response = post(request, JSONObject().put("action","complete").put("command_id",commandId).put("status",outcome.status.name).put("result",result))
        return when(response.status) { in 200..299 -> CommandCompletionResult.Success; 401 -> CommandCompletionResult.Unauthorized; else -> CommandCompletionResult.Failed(response.retryable) }
    }
    private fun post(request: DeviceCommandRequest, body: JSONObject): HttpResponse {
        val connection = URL(request.endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod="POST"; connection.connectTimeout=10_000; connection.readTimeout=10_000; connection.doOutput=true
            for ((key, value) in request.headers) connection.setRequestProperty(key, value)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status=connection.responseCode; val stream=if(status in 200..299) connection.inputStream else connection.errorStream
            val bytes=stream?.use { input -> val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096);var total=0;while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=64*1024);output.write(buffer,0,count)};output.toByteArray() }
            HttpResponse(status,bytes?.toString(Charsets.UTF_8),status==408||status==429||status>=500)
        } catch (_:Exception) { HttpResponse(null,null,true) } finally { connection.disconnect() }
    }
    private fun parseTime(value:String):Long = parseCommandTimestamp(value)
    private data class HttpResponse(val status:Int?,val body:String?,val retryable:Boolean)
}

internal fun parseCommandTimestamp(value: String): Long {
    val normalized = value
        .replace(Regex("(\\.\\d{3})\\d+(?=[Z+-])"), "$1")
        .replace("Z", "+0000")
        .replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(normalized)?.time ?: error("invalid command timestamp")
}

class SharedPreferencesExecutedCommandStore(context:Context,private val limit:Int=100):ExecutedCommandStore {
    private val preferences=context.getSharedPreferences("loopin_executed_commands",Context.MODE_PRIVATE)
    @Synchronized override fun find(commandId:String)=load().firstOrNull{it.first==commandId}?.second
    @Synchronized override fun remember(commandId:String,outcome:CommandOutcome){val entries=load().filterNot{it.first==commandId}.toMutableList();entries+=commandId to outcome;while(entries.size>limit)entries.removeAt(0);val array=JSONArray();entries.forEach{(id,value)->val result=JSONObject();value.result.forEach{(k,v)->result.put(k,v?:JSONObject.NULL)};array.put(JSONObject().put("id",id).put("status",value.status.name).put("result",result))};check(preferences.edit().putString("entries",array.toString()).commit())}
    private fun load():List<Pair<String,CommandOutcome>> = runCatching { val array=JSONArray(preferences.getString("entries","[]"));(0 until array.length()).map{index->val row=array.getJSONObject(index);val objectValue=row.getJSONObject("result");val result=linkedMapOf<String,Any?>();objectValue.keys().forEach{k->result[k]=if(objectValue.isNull(k))null else objectValue.get(k)};row.getString("id") to CommandOutcome(CommandCompletionStatus.valueOf(row.getString("status")),result)} }.getOrDefault(emptyList())
}

class DeviceCommandScheduler(private val context:Context,private val logger:PlayerLogger,private val backoff:CommandBackoffPolicy=CommandBackoffPolicy()) {
    private val preferences=context.getSharedPreferences("loopin_command_schedule_state",Context.MODE_PRIVATE)
    fun schedule(delayMs:Long=0):Boolean { val delay=delayMs.coerceAtLeast(0);val job=JobInfo.Builder(JOB_ID,ComponentName(context,DeviceCommandJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(delay).setOverrideDeadline(delay+60_000L).build();val result=(context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(job);if(result!=JobScheduler.RESULT_SUCCESS)logger.log(LogLevel.WARN,TAG,"Command scheduling failed");return result==JobScheduler.RESULT_SUCCESS }
    fun scheduleAfter(result:CommandDispatchResult){val previous=preferences.getInt("failures",0);val failures=when(result){is CommandDispatchResult.Failed->if(result.retryable)previous+1 else 0;CommandDispatchResult.Unauthorized->previous+1;else->0};preferences.edit().putInt("failures",failures).apply();schedule(backoff.delayAfter(result,failures))}
    private companion object{const val JOB_ID=0x4C50_0011;const val TAG="DeviceCommands"}
}

class DeviceCommandJobService:JobService() {
    @Volatile private var worker:Thread?=null
    override fun onStartJob(params:JobParameters):Boolean { val container=(application as LoopinApplication).container;worker=Thread({val result=runCatching{container.commandDispatcher.dispatch()}.getOrElse{CommandDispatchResult.Failed(true)};when(result){is CommandDispatchResult.Success->if(result.processed>0)container.logger.log(LogLevel.INFO,TAG,"Commands processed=${result.processed} confirmations_failed=${result.completionFailures}");CommandDispatchResult.Unauthorized->container.logger.log(LogLevel.WARN,TAG,"Command authentication failed");is CommandDispatchResult.Failed->container.logger.log(LogLevel.WARN,TAG,"Command fetch failed");else->Unit};jobFinished(params,false);Handler(Looper.getMainLooper()).post{container.commandScheduler.scheduleAfter(result)};worker=null},"loopin-commands").apply{start()};return true }
    override fun onStopJob(params:JobParameters):Boolean { worker?.interrupt();worker=null;(application as? LoopinApplication)?.container?.commandScheduler?.schedule(60_000);return false }
    private companion object{const val TAG="DeviceCommands"}
}
