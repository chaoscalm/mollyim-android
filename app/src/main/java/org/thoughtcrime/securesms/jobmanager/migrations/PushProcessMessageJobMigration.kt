package org.thoughtcrime.securesms.jobmanager.migrations

import okio.ByteString.Companion.toByteString
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobmanager.JobMigration
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobs.FailingJob
import org.thoughtcrime.securesms.jobs.PushProcessMessageErrorJob
import org.thoughtcrime.securesms.messages.MessageState
import org.thoughtcrime.securesms.util.Base64
import org.whispersystems.signalservice.api.crypto.protos.CompleteMessage
import org.whispersystems.signalservice.api.crypto.protos.EnvelopeMetadata
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.serialize.protos.SignalServiceContentProto

/**
 * Migrate PushProcessMessageJob V1 to V2 versions.
 */
class PushProcessMessageJobMigration : JobMigration(10) {
  override fun migrate(jobData: JobData): JobData {
    return if ("PushProcessJob" == jobData.factoryKey) {
      migrateJob(jobData)
    } else {
      jobData
    }
  }

  companion object {
    private val TAG = Log.tag(PushProcessMessageJobMigration::class.java)

    @Suppress("MoveVariableDeclarationIntoWhen")
    private fun migrateJob(jobData: JobData): JobData {
      val data = JsonJobData.deserialize(jobData.data)
      return if (data.hasInt("message_state")) {
        val state = MessageState.values()[data.getInt("message_state")]
        return when (state) {
          MessageState.NOOP -> jobData.withFactoryKey(FailingJob.KEY)

          MessageState.DECRYPTED_OK -> {
            try {
              migratePushProcessJobWithDecryptedData(jobData, data)
            } catch (t: Throwable) {
              Log.w(TAG, "Unable to migrate successful process job", t)
              jobData.withFactoryKey(FailingJob.KEY)
            }
          }

          else -> {
            Log.i(TAG, "Migrating push process error job for state: $state")
            jobData.withFactoryKey(PushProcessMessageErrorJob.KEY)
          }
        }
      } else {
        jobData.withFactoryKey(FailingJob.KEY)
      }
    }

    private fun migratePushProcessJobWithDecryptedData(jobData: JobData, inputData: JsonJobData): JobData {
      Log.i(TAG, "Migrating PushProcessJob to V2")

      val protoBytes: ByteArray = Base64.decode(inputData.getString("message_content"))
      val proto = SignalServiceContentProto.parseFrom(protoBytes)

      val sourceServiceId = ServiceId.parseOrThrow(proto.metadata.address.uuid)
      val destinationServiceId = ServiceId.parseOrThrow(proto.metadata.destinationUuid)

      val envelope = Envelope.newBuilder()
        .setSourceServiceId(sourceServiceId.toString())
        .setSourceDevice(proto.metadata.senderDevice)
        .setDestinationServiceId(destinationServiceId.toString())
        .setTimestamp(proto.metadata.timestamp)
        .setServerGuid(proto.metadata.serverGuid)
        .setServerTimestamp(proto.metadata.serverReceivedTimestamp)

      val metadata = EnvelopeMetadata(
        sourceServiceId = sourceServiceId.toByteArray().toByteString(),
        sourceE164 = if (proto.metadata.address.hasE164()) proto.metadata.address.e164 else null,
        sourceDeviceId = proto.metadata.senderDevice,
        sealedSender = proto.metadata.needsReceipt,
        groupId = if (proto.metadata.hasGroupId()) proto.metadata.groupId.toByteArray().toByteString() else null,
        destinationServiceId = destinationServiceId.toByteArray().toByteString()
      )

      val completeMessage = CompleteMessage(
        envelope = envelope.build().toByteArray().toByteString(),
        content = proto.content.toByteArray().toByteString(),
        metadata = metadata,
        serverDeliveredTimestamp = proto.metadata.serverDeliveredTimestamp
      )

      return jobData
        .withFactoryKey("PushProcessMessageJobV2")
        .withData(completeMessage.encode())
    }
  }
}
