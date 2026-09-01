package com.loopin.player2.core.sync
import com.loopin.player2.core.cache.*
import com.loopin.player2.core.model.MediaType
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*
class SyncManagerTest{
 @Test fun `204 preserves active`() { assertIs<SyncResult.NoAssignment>(manager(RemoteManifestResult.NoAssignment).syncOnce()) }
 @Test fun `304 is up to date`() { assertIs<SyncResult.UpToDate>(manager(RemoteManifestResult.Unchanged).syncOnce()) }
 @Test fun `401 is explicit`() { assertIs<SyncResult.AuthenticationFailed>(manager(RemoteManifestResult.AuthenticationFailed).syncOnce()) }
 @Test fun `offline is explicit`() { assertIs<SyncResult.Offline>(manager(RemoteManifestResult.Offline("offline")).syncOnce()) }
 @Test fun `failure remains classified`() { assertTrue((manager(RemoteManifestResult.Failed("server",true)).syncOnce() as SyncResult.Failed).retryable) }
 @Test fun `schema2 media commits`() { val bytes="media".toByteArray();val m=manifest(2,bytes);val root=createTempDirectory().toFile();val result=manager(RemoteManifestResult.Available(m,"a".repeat(64)),root,bytes).syncOnce();assertIs<SyncResult.Success>(result);assertEquals("playlist",TransactionalPlaylistStore(root,SpacePolicy{_,_->true}).publicationState()!!.active.playlistId) }
 @Test fun `weather does not request download`() { val root=createTempDirectory().toFile();var downloads=0;val mgr=SyncManager(RemoteManifestSource{RemoteManifestResult.Available(weatherManifest(),"b".repeat(64))},LocalManifestSource{null},RemoteMediaSourceFactory{downloads++;null},TransactionalPlaylistStore(root,SpacePolicy{_,_->true}));assertIs<SyncResult.Success>(mgr.syncOnce());assertEquals(0,downloads) }
 @Test fun `retry policy is bounded`() { val p=SyncRetryPolicy();assertEquals(listOf(60000L,300000L,900000L,1800000L), (1..4).map(p::delayAfterFailure)) }
 @Test fun `authentication backoff is one hour`() { assertEquals(3_600_000L,SyncRetryPolicy().authDelayMs) }
 @Test fun `normal interval is five minutes`() { assertEquals(300_000L,SyncRetryPolicy().regularIntervalMs) }
 @Test fun `invalid endpoint is non retryable`() { val r=AuthenticatedRemoteManifestSource("invalid",{"secret"}).fetch(null) as RemoteManifestResult.Failed;assertFalse(r.retryable) }
 @Test fun `missing credential fails authentication without network`() { assertIs<RemoteManifestResult.AuthenticationFailed>(AuthenticatedRemoteManifestSource("https://example.invalid",{null}).fetch(null)) }
 private fun manager(result:RemoteManifestResult,root:File=createTempDirectory().toFile(),bytes:ByteArray="x".toByteArray())=SyncManager(RemoteManifestSource{result},LocalManifestSource{null},RemoteMediaSourceFactory{MediaSource{ByteArrayInputStream(bytes)}},TransactionalPlaylistStore(root,SpacePolicy{_,_->true}))
 private fun manifest(v:Long,b:ByteArray)=VersionedManifest(2,"playlist",v,v,listOf(NormalizedManifestItem("media",0,NormalMediaContent(MediaType.VIDEO,"asset",null,b.size.toLong(),sha(b),"video/mp4"))))
 private fun weatherManifest()=VersionedManifest(2,"weather",1,1,listOf(NormalizedManifestItem("w",0,DynamicMediaContent(ManifestDynamicType.WEATHER,1000,mapOf("city" to "São José do Piauí","lat" to "-7.08","lon" to "-41.47")))))
 private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
}
