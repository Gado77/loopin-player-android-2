package com.loopin.player2

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.loopin.player2.core.sync.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class SharedPreferencesPreparedUpdateStore(private val context:Context):PreparedUpdateStore {
    private val prefs=context.getSharedPreferences("loopin_prepared_update",Context.MODE_PRIVATE)
    override fun load():ApkPreparationResult.Ready?=runCatching { val o=JSONObject(prefs.getString("value",null)?:return null);val info=PlayerUpdateInfo(o.getString("releaseId"),o.getLong("versionCode"),o.getString("versionName"),o.getString("downloadUrl"),o.getLong("sizeBytes"),o.getString("sha256"),o.getString("packageName"),o.getString("certificateSha256"),o.getString("releaseChannel"),o.optString("releaseNotes").takeIf(String::isNotBlank));ApkPreparationResult.Ready(info,File(o.getString("path"))) }.getOrNull()
    override fun save(value:ApkPreparationResult.Ready){val i=value.info;val o=JSONObject().put("releaseId",i.releaseId).put("versionCode",i.versionCode).put("versionName",i.versionName).put("downloadUrl",i.downloadUrl).put("sizeBytes",i.sizeBytes).put("sha256",i.sha256).put("packageName",i.packageName).put("certificateSha256",i.certificateSha256).put("releaseChannel",i.releaseChannel).put("releaseNotes",i.releaseNotes).put("path",value.apk.absolutePath);check(prefs.edit().putString("value",o.toString()).commit())}
    override fun clear(){prefs.edit().clear().commit()}
}

class SharedPreferencesUpdateAttemptStore(private val context:Context):UpdateInstallAttemptStore {
    private val prefs=context.getSharedPreferences("loopin_update_attempt",Context.MODE_PRIVATE)
    override fun load():UpdateInstallAttempt?=runCatching{val o=JSONObject(prefs.getString("value",null)?:return null);UpdateInstallAttempt(o.getString("releaseId"),o.getLong("from"),o.getLong("target"),o.getString("name"),o.getString("sha"),o.getLong("started"),UpdateInstallationState.valueOf(o.getString("state")),o.optString("failure").takeIf(String::isNotBlank))}.getOrNull()
    override fun save(value:UpdateInstallAttempt){val o=JSONObject().put("releaseId",value.releaseId).put("from",value.fromVersionCode).put("target",value.targetVersionCode).put("name",value.targetVersionName).put("sha",value.apkSha256).put("started",value.startedAtEpochMs).put("state",value.state.name).put("failure",value.failureCode);check(prefs.edit().putString("value",o.toString()).commit())}
    override fun clear(){prefs.edit().clear().commit()}
}

object PendingInstallConfirmationBridge {
    private val pending=AtomicReference<Intent?>()
    private val foreground=AtomicReference<((Intent)->Unit)?>(null)
    fun publish(intent:Intent){pending.set(intent);foreground.get()?.invoke(intent)}
    fun attach(callback:(Intent)->Unit){foreground.set(callback);pending.getAndSet(null)?.let(callback)}
    fun detach(){foreground.set(null)}
}

class AndroidPackageUpdateInstaller(private val context:Context):PlatformUpdateInstaller {
    override fun capability():InstallationCapability {
        val owner=(context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(context.packageName)
        if(owner)return InstallationCapability.DEVICE_OWNER
        if(Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls())return InstallationCapability.INTERACTIVE_PERMISSION_REQUIRED
        return InstallationCapability.INTERACTIVE_READY
    }
    override fun install(apk:File,attempt:UpdateInstallAttempt):PlatformInstallResult {
        if(capability()==InstallationCapability.INTERACTIVE_PERMISSION_REQUIRED)return PlatformInstallResult.PermissionRequired
        return try{
            val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply{
                setAppPackageName(context.packageName)
                if(Build.VERSION.SDK_INT>=31&&(context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(context.packageName))setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val installer=context.packageManager.packageInstaller
            val sessionId=installer.createSession(params)
            installer.openSession(sessionId).use{session->apk.inputStream().buffered().use{input->session.openWrite("player.apk",0,apk.length()).use{output->input.copyTo(output,DEFAULT_BUFFER_SIZE);session.fsync(output)}};val intent=Intent(context,UpdateInstallResultReceiver::class.java).putExtra("release_id",attempt.releaseId).putExtra("target_version",attempt.targetVersionCode);val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0;session.commit(PendingIntent.getBroadcast(context,sessionId,intent,flags).intentSender)}
            PlatformInstallResult.Requested
        }catch(e:Exception){PlatformInstallResult.Failed("PACKAGE_INSTALLER_${e.javaClass.simpleName.uppercase().take(32)}")}
    }
}

class UpdateInstallResultReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val store=SharedPreferencesUpdateAttemptStore(context);val attempt=store.load()?:return
        val updated=when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)){
            PackageInstaller.STATUS_SUCCESS->attempt.copy(state=UpdateInstallationState.POST_UPDATE_VERIFYING)
            PackageInstaller.STATUS_PENDING_USER_ACTION->{@Suppress("DEPRECATION") val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT);if(confirmation!=null)PendingInstallConfirmationBridge.publish(confirmation);attempt.copy(state=UpdateInstallationState.USER_ACTION_REQUIRED)}
            PackageInstaller.STATUS_FAILURE_ABORTED->attempt.copy(state=UpdateInstallationState.INSTALL_CANCELED,failureCode="ABORTED")
            PackageInstaller.STATUS_FAILURE_BLOCKED->attempt.copy(state=UpdateInstallationState.INSTALL_FAILED,failureCode="BLOCKED")
            PackageInstaller.STATUS_FAILURE_CONFLICT->attempt.copy(state=UpdateInstallationState.INSTALL_FAILED,failureCode="CONFLICT")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE->attempt.copy(state=UpdateInstallationState.INSTALL_FAILED,failureCode="INCOMPATIBLE")
            PackageInstaller.STATUS_FAILURE_INVALID->attempt.copy(state=UpdateInstallationState.INSTALL_FAILED,failureCode="INVALID")
            PackageInstaller.STATUS_FAILURE_STORAGE->attempt.copy(state=UpdateInstallationState.INSTALL_FAILED,failureCode="STORAGE")
            else->attempt.copy(state=UpdateInstallationState.INSTALL_FAILED,failureCode="PACKAGE_INSTALLER_FAILURE")
        }
        store.save(updated)
        val pending=goAsync();Thread({try{AuthenticatedPlayerUpdateSource(BuildConfig.UPDATE_ENDPOINT,DeviceCredentialStore(context)::credential,BuildConfig.VERSION_CODE.toLong()).reportInstall(updated)}finally{pending.finish()}},"loopin-update-report").start()
    }
}

class PackageReplacedReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_MY_PACKAGE_REPLACED){val s=SharedPreferencesUpdateAttemptStore(context);s.load()?.let{s.save(it.copy(state=UpdateInstallationState.POST_UPDATE_VERIFYING))}}}}
