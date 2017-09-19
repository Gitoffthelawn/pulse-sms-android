/*
 * Copyright (C) 2017 Luke Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.klinker.messenger.api.implementation

import android.content.Context
import android.util.Log

import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

import retrofit2.Call
import retrofit2.Response
import xyz.klinker.messenger.api.Api
import xyz.klinker.messenger.api.entity.AddBlacklistRequest
import xyz.klinker.messenger.api.entity.AddContactRequest
import xyz.klinker.messenger.api.entity.AddConversationRequest
import xyz.klinker.messenger.api.entity.AddDeviceRequest
import xyz.klinker.messenger.api.entity.AddDeviceResponse
import xyz.klinker.messenger.api.entity.AddDraftRequest
import xyz.klinker.messenger.api.entity.AddMessagesRequest
import xyz.klinker.messenger.api.entity.AddScheduledMessageRequest
import xyz.klinker.messenger.api.entity.BlacklistBody
import xyz.klinker.messenger.api.entity.ContactBody
import xyz.klinker.messenger.api.entity.ConversationBody
import xyz.klinker.messenger.api.entity.DeviceBody
import xyz.klinker.messenger.api.entity.DraftBody
import xyz.klinker.messenger.api.entity.LoginRequest
import xyz.klinker.messenger.api.entity.LoginResponse
import xyz.klinker.messenger.api.entity.MessageBody
import xyz.klinker.messenger.api.entity.ScheduledMessageBody
import xyz.klinker.messenger.api.entity.SignupRequest
import xyz.klinker.messenger.api.entity.SignupResponse
import xyz.klinker.messenger.api.entity.UpdateContactRequest
import xyz.klinker.messenger.api.entity.UpdateConversationRequest
import xyz.klinker.messenger.api.entity.UpdateMessageRequest
import xyz.klinker.messenger.api.entity.UpdateScheduledMessageRequest
import xyz.klinker.messenger.api.implementation.firebase.FirebaseDownloadCallback
import xyz.klinker.messenger.api.implementation.firebase.FirebaseUploadCallback
import xyz.klinker.messenger.api.implementation.retrofit.LoggingRetryableCallback
import xyz.klinker.messenger.encryption.EncryptionUtils

/**
 * Utility for easing access to APIs.
 */
object ApiUtils {
    val RETRY_COUNT = 3

    private val TAG = "ApiUtils"
    private val MAX_SIZE = (1024 * 1024 * 5).toLong()
    private val FIREBASE_STORAGE_URL = "gs://messenger-42616.appspot.com"

    fun isCallSuccessful(response: Response<*>): Boolean {
        val code = response.code()
        return code in 200..399
    }

    /**
     * Gets direct access to the apis for more advanced options.
     */
    var environment = "release"
    val api: Api by lazy { ApiAccessor.create(environment) }
    private var folderRef: StorageReference? = null

    /**
     * Logs into the server.
     */
    fun login(email: String?, password: String?): LoginResponse? {
        return try {
            val request = LoginRequest(email, password)
            api.account().login(request).execute().body()
        } catch (e: IOException) {
            null
        }

    }

    /**
     * Signs up for the service.
     */
    fun signup(email: String?, password: String?, name: String?, phoneNumber: String?): SignupResponse? {
        return try {
            val request = SignupRequest(email, name, password, phoneNumber)
            api.account().signup(request).execute().body()
        } catch (e: IOException) {
            null
        }

    }

    /**
     * Removes the account from the server.
     */
    fun deleteAccount(accountId: String?) {
        val message = "removed account"
        val call = api.account().remove(accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Cleans all the database tables, for the account, on the server
     */
    fun cleanAccount(accountId: String?) {
        val message = "cleaned account"
        val call = api.account().clean(accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Registers your device as a new device on the server.
     */
    fun registerDevice(accountId: String?, info: String?, name: String?,
                       primary: Boolean, fcmToken: String?): Int? {
        val deviceBody = DeviceBody(info, name, primary, fcmToken)
        val request = AddDeviceRequest(accountId, deviceBody)

        try {
            val response = api.device().add(request).execute().body()
            if (response != null) {
                return response.id
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * Removes a device from the server.
     */
    fun removeDevice(accountId: String?, deviceId: Int) {
        val message = "remove device"
        val call = api.device().remove(deviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    fun updatePrimaryDevice(accountId: String?, newPrimaryDeviceId: String?) {
        if (accountId == null) {
            return
        }

        val message = "update primary device"
        val call = api.device().updatePrimary(newPrimaryDeviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Gets a list of all devices on the server.
     */
    fun getDevices(accountId: String?): Array<DeviceBody>? {
        return try {
            api.device().list(accountId).execute().body()
        } catch (e: IOException) {
            emptyArray()
        }

    }

    /**
     * Updates device info on the server.
     */
    fun updateDevice(accountId: String?, deviceId: Long, name: String?, fcmToken: String?) {
        val message = "update device"
        val call = api.device().update(deviceId, accountId, name, fcmToken)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Adds a new contact.
     */
    fun addContact(accountId: String?, phoneNumber: String?, name: String?, color: Int,
                   colorDark: Int, colorLight: Int, colorAccent: Int,
                   encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val body = ContactBody(
                encryptionUtils.encrypt(phoneNumber), encryptionUtils.encrypt(name),
                color, colorDark, colorLight, colorAccent)
        val request = AddContactRequest(accountId, body)

        addContact(request)
    }

    /**
     * Adds a new contact.
     */
    fun addContact(request: AddContactRequest) {
        val message = "add contact"
        val call = api.contact().add(request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes a contact
     */
    fun deleteContact(accountId: String?, phoneNumber: String?,
                      encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val message = "delete contact"
        val call = api.contact().remove(encryptionUtils.encrypt(phoneNumber), accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes a conversation and all of its messages.
     */
    fun clearContacts(accountId: String?) {
        if (accountId == null) {
            return
        }

        val message = "delete contact"
        val call = api.contact().clear(accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Updates a conversation with new settings or info.
     */
    fun updateContact(accountId: String?, phoneNumber: String?, name: String?,
                      color: Int?, colorDark: Int?, colorLight: Int?,
                      colorAccent: Int?,
                      encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val request = UpdateContactRequest(
                encryptionUtils.encrypt(phoneNumber), encryptionUtils.encrypt(name),
                color, colorDark, colorLight, colorAccent)

        val message = "update contact"
        val call = api.contact().update(encryptionUtils.encrypt(phoneNumber), accountId, request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Adds a new conversation.
     */
    fun addConversation(accountId: String?, deviceId: Long, color: Int,
                        colorDark: Int, colorLight: Int, colorAccent: Int,
                        ledColor: Int, pinned: Boolean, read: Boolean, timestamp: Long,
                        title: String?, phoneNumbers: String?, snippet: String?,
                        ringtone: String?, idMatcher: String?, mute: Boolean,
                        archive: Boolean, privateNotifications: Boolean,
                        encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val body = ConversationBody(
                deviceId, color, colorDark, colorLight, colorAccent, ledColor,
                pinned, read, timestamp, encryptionUtils.encrypt(title),
                encryptionUtils.encrypt(phoneNumbers), encryptionUtils.encrypt(snippet),
                encryptionUtils.encrypt(ringtone), null,
                encryptionUtils.encrypt(idMatcher), mute, archive, privateNotifications)
        val request = AddConversationRequest(accountId, body)

        val message = "add conversation"
        val call = api.conversation().add(request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes a conversation and all of its messages.
     */
    fun deleteConversation(accountId: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "delete conversation"
        val call = api.conversation().remove(deviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Archives a conversation.
     */
    fun archiveConversation(accountId: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "archive conversation"
        val call = api.conversation().archive(deviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Moves a conversation back to the inbox.
     */
    fun unarchiveConversation(accountId: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "unarchive conversation"
        val call = api.conversation().unarchive(deviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Updates a conversation with new settings or info.
     */
    fun updateConversation(accountId: String?, deviceId: Long, color: Int?,
                           colorDark: Int?, colorLight: Int?,
                           colorAccent: Int?, ledColor: Int?, pinned: Boolean?,
                           read: Boolean?, timestamp: Long?, title: String?,
                           snippet: String?, ringtone: String?, mute: Boolean?,
                           archive: Boolean?, privateNotifications: Boolean?,
                           encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val request = UpdateConversationRequest(color,
                colorDark, colorLight, colorAccent, ledColor, pinned, read, timestamp,
                encryptionUtils.encrypt(title), encryptionUtils.encrypt(snippet),
                encryptionUtils.encrypt(ringtone), mute, archive, privateNotifications)

        val message = "update conversation"
        val call = api.conversation().update(deviceId, accountId, request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Updates a conversation with new snippet info
     */
    fun updateConversationSnippet(accountId: String?, deviceId: Long,
                                  read: Boolean?, archive: Boolean?,
                                  timestamp: Long?, snippet: String?,
                                  encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val request = UpdateConversationRequest(null, null, null, null, null, null, read, timestamp, null, encryptionUtils.encrypt(snippet), null, null, archive, null)

        val message = "update conversation snippet"
        val call = api.conversation().updateSnippet(deviceId, accountId, request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Updates a conversation with a new title (usually when the name changes)
     */
    fun updateConversationTitle(accountId: String?, deviceId: Long,
                                title: String?, encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val message = "update conversation title"
        val call = api.conversation().updateTitle(deviceId, accountId, encryptionUtils.encrypt(title))

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Marks all messages in conversation as read.
     */
    fun readConversation(accountId: String?, androidDevice: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "read conversation"
        val call = api.conversation().read(deviceId, androidDevice, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Marks all messages in conversation as seen.
     */
    fun seenConversation(accountId: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "seen conversation"
        val call = api.conversation().seen(deviceId, accountId)

        //call.enqueue(new LoggingRetryableCallback<>(call, RETRY_COUNT, message));
    }

    /**
     * Marks all messages as seen.
     */
    fun seenConversations(accountId: String?) {
        if (accountId == null) {
            return
        }

        val message = "seen all conversation"
        val call = api.conversation().seen(accountId)

        //call.enqueue(new LoggingRetryableCallback<>(call, RETRY_COUNT, message));
    }

    /**
     * Adds a new message to the server.
     */
    fun addMessage(context: Context, accountId: String?, deviceId: Long,
                   deviceConversationId: Long, messageType: Int,
                   data: String?, timestamp: Long, mimeType: String?,
                   read: Boolean, seen: Boolean, messageFrom: String?,
                   color: Int?, encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        if (mimeType == "text/plain" || messageType == 6) {
            val body = MessageBody(deviceId,
                    deviceConversationId, messageType, encryptionUtils.encrypt(data),
                    timestamp, encryptionUtils.encrypt(mimeType), read, seen,
                    encryptionUtils.encrypt(messageFrom), color)
            val request = AddMessagesRequest(accountId, body)
            val message = "add message"
            val call = api.message().add(request)

            call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
        } else {
            saveFirebaseFolderRef(accountId)
            val bytes = BinaryUtils.getMediaBytes(context, data, mimeType)
            uploadBytesToFirebase(accountId, bytes, deviceId, encryptionUtils, FirebaseUploadCallback {
                val body = MessageBody(deviceId, deviceConversationId,
                        messageType, encryptionUtils.encrypt("firebase -1"),
                        timestamp, encryptionUtils.encrypt(mimeType), read, seen,
                        encryptionUtils.encrypt(messageFrom), color)
                val request = AddMessagesRequest(accountId, body)
                val message = "add media message"
                val call = api.message().add(request)

                call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
            }, 0)
        }
    }

    /**
     * Updates a message with the given parameters.
     */
    fun updateMessage(accountId: String?, deviceId: Long, type: Int?,
                      read: Boolean?, seen: Boolean?) {
        if (accountId == null) {
            return
        }

        val request = UpdateMessageRequest(type, read, seen)
        val message = "update message"
        val call = api.message().update(deviceId, accountId, request)

        call.enqueue(LoggingRetryableCallback(call, 6, message))
    }

    /**
     * Updates a message with the given parameters.
     */
    fun updateMessageType(accountId: String?, deviceId: Long, type: Int) {
        if (accountId == null) {
            return
        }

        val message = "update message type"
        val call = api.message().updateType(deviceId, accountId, type)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes the given message.
     */
    fun deleteMessage(accountId: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "delete message"
        val call = api.message().remove(deviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes messages older than the given timestamp.
     */
    fun cleanupMessages(accountId: String?, timestamp: Long) {
        if (accountId == null) {
            return
        }

        val message = "clean up messages"
        val call = api.message().cleanup(accountId, timestamp)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Adds a draft.
     */
    fun addDraft(accountId: String?, deviceId: Long,
                 deviceConversationId: Long, data: String?,
                 mimeType: String?, encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val body = DraftBody(deviceId, deviceConversationId,
                encryptionUtils.encrypt(data), encryptionUtils.encrypt(mimeType))
        val request = AddDraftRequest(accountId, body)

        val message = "add draft"
        val call = api.draft().add(request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes the given drafts.
     */
    fun deleteDrafts(accountId: String?, androidDeviceId: String?, deviceConversationId: Long) {
        if (accountId == null) {
            return
        }

        val message = "delete drafts"
        val call = api.draft().remove(deviceConversationId, androidDeviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Adds a blacklist.
     */
    fun addBlacklist(accountId: String?, deviceId: Long, phoneNumber: String?,
                     encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val body = BlacklistBody(deviceId,
                encryptionUtils.encrypt(phoneNumber))
        val request = AddBlacklistRequest(accountId, body)

        val message = "add blacklist"
        val call = api.blacklist().add(request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes the given blacklist.
     */
    fun deleteBlacklist(accountId: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "delete blacklist"
        val call = api.blacklist().remove(deviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Adds a scheduled message.
     */
    fun addScheduledMessage(accountId: String?, deviceId: Long, title: String?,
                            to: String?, data: String?, mimeType: String?,
                            timestamp: Long, encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val body = ScheduledMessageBody(
                deviceId,
                encryptionUtils.encrypt(to),
                encryptionUtils.encrypt(data),
                encryptionUtils.encrypt(mimeType),
                timestamp,
                encryptionUtils.encrypt(title))

        val request = AddScheduledMessageRequest(accountId, body)

        val message = "add scheduled message"
        val call = api.scheduled().add(request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    fun updateScheduledMessage(accountId: String?, deviceId: Long, title: String?,
                               to: String?, data: String?, mimeType: String?,
                               timestamp: Long, encryptionUtils: EncryptionUtils?) {
        if (accountId == null || encryptionUtils == null) {
            return
        }

        val request = UpdateScheduledMessageRequest(
                encryptionUtils.encrypt(to), encryptionUtils.encrypt(data),
                encryptionUtils.encrypt(mimeType), timestamp,
                encryptionUtils.encrypt(title))

        val message = "update scheduled message"
        val call = api.scheduled().update(deviceId, accountId, request)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Deletes the given scheduled message.
     */
    fun deleteScheduledMessage(accountId: String?, deviceId: Long) {
        if (accountId == null) {
            return
        }

        val message = "delete scheduled message"
        val call = api.scheduled().remove(deviceId, accountId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Uploads a byte array of encrypted data to firebase.
     *
     * @param bytes the byte array to upload.
     * @param messageId the message id that the data belongs to.
     * @param encryptionUtils the utils to encrypt the byte array with.
     */
    fun uploadBytesToFirebase(accountId: String?, bytes: ByteArray, messageId: Long, encryptionUtils: EncryptionUtils?,
                              callback: FirebaseUploadCallback, retryCount: Int) {
        if (encryptionUtils == null || retryCount > RETRY_COUNT) {
            callback.onUploadFinished()
            return
        }

        if (folderRef == null) {
            saveFirebaseFolderRef(accountId)
            if (folderRef == null) {
                //                throw new RuntimeException("need to initialize folder ref first with saveFolderRef()");
                callback.onUploadFinished()
                return
            }
        }

        try {
            val fileRef = folderRef!!.child(messageId.toString() + "")
            fileRef.putBytes(encryptionUtils.encrypt(bytes).toByteArray())
                    .addOnSuccessListener {
                        Log.v(TAG, "finished uploading and exiting for " + messageId)
                        callback.onUploadFinished()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "failed to upload file", e)
                        uploadBytesToFirebase(accountId, bytes, messageId, encryptionUtils, callback, retryCount + 1)
                    }
        } catch (e: Exception) {
            callback.onUploadFinished()
        }

    }

    /**
     * Downloads and decrypts a file from firebase, using a callback for when the response is done
     *
     * @param file the location on your device to save to.
     * @param messageId the id of the message to grab so we can create a firebase storage ref.
     * @param encryptionUtils the utils to use to decrypt the message.
     */
    fun downloadFileFromFirebase(accountId: String?, file: File, messageId: Long,
                                 encryptionUtils: EncryptionUtils?,
                                 callback: FirebaseDownloadCallback, retryCount: Int) {
        if (encryptionUtils == null || retryCount > RETRY_COUNT) {
            callback.onDownloadComplete()
            return
        }

        if (folderRef == null) {
            saveFirebaseFolderRef(accountId)
            if (folderRef == null) {
                //                throw new RuntimeException("need to initialize folder ref first with saveFolderRef()");
                callback.onDownloadComplete()
                return
            }
        }

        try {
            val fileRef = folderRef!!.child(messageId.toString() + "")
            fileRef.getBytes(MAX_SIZE)
                    .addOnSuccessListener { bytes ->
                        val bytes = encryptionUtils.decryptData(String(bytes))

                        try {
                            val bos = BufferedOutputStream(FileOutputStream(file))
                            bos.write(bytes)
                            bos.flush()
                            bos.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        Log.v(TAG, "finished downloading " + messageId)
                        callback.onDownloadComplete()
                    }
                    .addOnFailureListener { e ->
                        Log.v(TAG, "failed to download file", e)
                        val doesNotExist = e.message?.contains("does not exist")
                        if (doesNotExist != null && doesNotExist) {
                            downloadFileFromFirebase(accountId, file, messageId, encryptionUtils, callback, retryCount + 1)
                        } else {
                            callback.onDownloadComplete()
                        }
                    }
        } catch (e: Exception) {
            callback.onDownloadComplete()
        }
    }

    /**
     * Creates a ref to a folder where all media will be stored for this user.
     */
    fun saveFirebaseFolderRef(accountId: String?) {
        if (accountId == null) {
            return
        }

        val storage = FirebaseStorage.getInstance()
        val storageRef = storage.getReferenceFromUrl(FIREBASE_STORAGE_URL)
        folderRef = try {
            storageRef.child(accountId)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                storageRef.child(accountId)
            } catch (ex: Exception) {
                null
            }

        }

    }

    /**
     * Dismiss a notification across all devices.
     */
    fun dismissNotification(accountId: String?, deviceId: String?, conversationId: Long) {
        if (accountId == null) {
            return
        }

        val message = "dismiss notification"
        val call = api.account().dismissedNotification(accountId, deviceId, conversationId)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Update the subscription status on the server.
     */
    fun updateSubscription(accountId: String?, subscriptionType: Int?, expirationDate: Long?) {
        if (accountId == null) {
            return
        }

        val message = "update subscription"
        val call = api.account().updateSubscription(accountId, subscriptionType!!, expirationDate!!)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }

    /**
     * Update the snooze time setting.
     */
    fun updateSnooze(accountId: String?, snoozeTil: Long) {
        if (accountId != null) {
            updateSetting(accountId, "snooze", "long", snoozeTil)
        }
    }

    /**
     * Update the vibrate setting.
     */
    fun updateVibrate(accountId: String?, vibratePattern: String?) {
        if (accountId != null) {
            updateSetting(accountId, "vibrate_pattern_identifier", "string", vibratePattern)
        }
    }

    /**
     * Update the repeat notifications setting.
     */
    fun updateRepeatNotifications(accountId: String?, repeatString: String?) {
        if (accountId != null) {
            updateSetting(accountId, "repeat_notifications_interval", "string", repeatString)
        }
    }

    /**
     * Update the wake screen setting
     */
    fun updateWakeScreen(accountId: String?, wake: String?) {
        if (accountId != null) {
            updateSetting(accountId, "wake_screen", "string", wake)
        }
    }

    /**
     * Update the wake screen setting
     */
    fun updateHeadsUp(accountId: String?, headsUp: String?) {
        if (accountId != null) {
            updateSetting(accountId, "heads_up", "string", headsUp)
        }
    }

    /**
     * Update the delivery reports setting.
     */
    fun updateDeliveryReports(accountId: String?, deliveryReports: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "delivery_reports", "boolean", deliveryReports)
        }
    }

    /**
     * Update the delivery reports setting.
     */
    fun updateGiffgaffDeliveryReports(accountId: String?, deliveryReports: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "giffgaff_delivery", "boolean", deliveryReports)
        }
    }

    /**
     * Update the strip Unicode setting.
     */
    fun updateStripUnicode(accountId: String?, stripUnicode: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "strip_unicode", "boolean", stripUnicode)
        }
    }

    /**
     * Update the notification history option
     */
    fun updateShowHistoryInNotification(accountId: String?, showHistory: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "history_in_notifications", "boolean", showHistory)
        }
    }

    /**
     * Update the rounder bubbles setting.
     */
    fun updateRounderBubbles(accountId: String?, rounderBubbles: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "rounder_bubbles", "boolean", rounderBubbles)
        }
    }

    /**
     * Update the notification actions setting.
     */
    fun updateNotificationActions(accountId: String?, stringified: String?) {
        if (accountId != null) {
            updateSetting(accountId, "notification_actions", "set", stringified)
        }
    }

    /**
     * Update the swipe to delete setting
     */
    fun updateSwipeToDelete(accountId: String?, swipeDelete: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "swipe_delete", "boolean", swipeDelete)
        }
    }

    /**
     * Update the convert to MMS setting, for long messages
     */
    fun updateConvertToMMS(accountId: String?, convert: String?) {
        if (accountId != null) {
            updateSetting(accountId, "sms_to_mms_message_conversion_count", "string", convert)
        }
    }

    /**
     * Update the MMS size limit setting.
     */
    fun updateMmsSize(accountId: String?, mmsSize: String?) {
        if (accountId != null) {
            updateSetting(accountId, "mms_size_limit", "string", mmsSize)
        }
    }

    /**
     * Update the group MMS setting.
     */
    fun updateGroupMMS(accountId: String?, groupMMS: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "group_mms", "boolean", groupMMS)
        }
    }

    /**
     * Update the auto save media setting.
     */
    fun updateAutoSaveMedia(accountId: String?, save: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "auto_save_media", "boolean", save)
        }
    }

    /**
     * Update the override system apn setting.
     */
    fun updateOverrideSystemApn(accountId: String?, override: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "mms_override", "boolean", override)
        }
    }

    /**
     * Update the mmsc url for MMS.
     */
    fun updateMmscUrl(accountId: String?, mmsc: String?) {
        if (accountId != null) {
            updateSetting(accountId, "mmsc_url", "string", mmsc)
        }
    }

    /**
     * Update the MMS proxy setting.
     */
    fun updateMmsProxy(accountId: String?, proxy: String?) {
        if (accountId != null) {
            updateSetting(accountId, "mms_proxy", "string", proxy)
        }
    }

    /**
     * Update the MMS port setting.
     */
    fun updateMmsPort(accountId: String?, port: String?) {
        if (accountId != null) {
            updateSetting(accountId, "mms_port", "string", port)
        }
    }

    /**
     * Update the user agent setting.
     */
    fun updateUserAgent(accountId: String?, userAgent: String?) {
        if (accountId != null) {
            updateSetting(accountId, "user_agent", "string", userAgent)
        }
    }

    /**
     * Update the user agent profile url setting.
     */
    fun updateUserAgentProfileUrl(accountId: String?, userAgentProfileUrl: String?) {
        if (accountId != null) {
            updateSetting(accountId, "user_agent_profile_url", "string", userAgentProfileUrl)
        }
    }

    /**
     * Update the user agent tag name setting.
     */
    fun updateUserAgentProfileTagName(accountId: String?, tagName: String?) {
        if (accountId != null) {
            updateSetting(accountId, "user_agent_profile_tag_name", "string", tagName)
        }
    }

    /**
     * Update the secure private conversations setting.
     */
    fun updateSecurePrivateConversations(accountId: String?, secure: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "secure_private_conversations", "boolean", secure)
        }
    }

    /**
     * Update the quick compose setting.
     */
    fun updateQuickCompose(accountId: String?, quickCompose: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "quick_compose", "boolean", quickCompose)
        }
    }

    /**
     * Update the signature setting.
     */
    fun updateSignature(accountId: String?, signature: String?) {
        if (accountId != null) {
            updateSetting(accountId, "signature", "string", signature)
        }
    }


    /**
     * Update the delayed sending setting.
     */
    fun updateDelayedSending(accountId: String?, delayedSending: String?) {
        if (accountId != null) {
            updateSetting(accountId, "delayed_sending", "string", delayedSending)
        }
    }


    /**
     * Update the cleanup old messages setting.
     */
    fun updateCleanupOldMessages(accountId: String?, cleanup: String?) {
        if (accountId != null) {
            updateSetting(accountId, "cleanup_old_messages", "string", cleanup)
        }
    }

    /**
     * Update the sound effects setting.
     */
    fun updateSoundEffects(accountId: String?, effects: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "sound_effects", "boolean", effects)
        }
    }

    /**
     * Update the mobile only setting
     */
    fun updateMobileOnly(accountId: String?, mobileOnly: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "mobile_only", "boolean", mobileOnly)
        }
    }

    /**
     * Update the font size setting
     */
    fun updateFontSize(accountId: String?, size: String?) {
        if (accountId != null) {
            updateSetting(accountId, "font_size", "string", size)
        }
    }

    /**
     * Update the emoji style setting
     */
    fun updateEmojiStyle(accountId: String?, style: String?) {
        if (accountId != null) {
            updateSetting(accountId, "emoji_style", "string", style)
        }
    }

    /**
     * Update the keyboard layout setting
     */
    fun updateKeyboardLayout(accountId: String?, layout: String?) {
        if (accountId != null) {
            updateSetting(accountId, "keyboard_layout", "string", layout)
        }
    }

    /**
     * Update the global theme color setting
     */
    fun updatePrimaryThemeColor(accountId: String?, color: Int) {
        if (accountId != null) {
            updateSetting(accountId, "global_primary_color", "int", color)
        }
    }

    /**
     * Update the global theme color setting
     */
    fun updatePrimaryDarkThemeColor(accountId: String?, color: Int) {
        if (accountId != null) {
            updateSetting(accountId, "global_primary_dark_color", "int", color)
        }
    }

    /**
     * Update the global theme color setting
     */
    fun updateAccentThemeColor(accountId: String?, color: Int) {
        if (accountId != null) {
            updateSetting(accountId, "global_accent_color", "int", color)
        }
    }

    /**
     * Update the mobile only setting
     */
    fun updateUseGlobalTheme(accountId: String?, useGlobal: Boolean) {
        if (accountId != null) {
            updateSetting(accountId, "apply_theme_globally", "boolean", useGlobal)
        }
    }

    /**
     * Update the base theme (day/night, always dark, always black)
     */
    fun updateBaseTheme(accountId: String?, themeString: String?) {
        if (accountId != null) {
            updateSetting(accountId, "base_theme", "string", themeString)
        }
    }

    /**
     * Dismiss a notification across all devices.
     */
    private fun updateSetting(accountId: String?, pref: String?, type: String?, value: Any?) {
        val message = "update $pref setting"
        val call = api.account().updateSetting(accountId, pref, type, value)

        call.enqueue(LoggingRetryableCallback(call, RETRY_COUNT, message))
    }
}