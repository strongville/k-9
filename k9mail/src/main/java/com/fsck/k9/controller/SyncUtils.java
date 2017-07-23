package com.fsck.k9.controller;


import java.util.Collections;
import java.util.Date;
import java.util.List;

import android.content.Context;

import com.fsck.k9.Account;
import com.fsck.k9.cache.EmailProviderCache;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Folder.FolderType;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import timber.log.Timber;


class SyncUtils {

    static LocalFolder getOpenedLocalFolder(Account account, String folderName) throws MessagingException{
        final LocalStore localStore = account.getLocalStore();
        LocalFolder localFolder = localStore.getFolder(folderName);
        localFolder.open(Folder.OPEN_MODE_RW);
        return localFolder;
    }

    /*
     * If the folder is a "special" folder we need to see if it exists
     * on the remote server. It if does not exist we'll try to create it. If we
     * can't create we'll abort. This will happen on every single Pop3 folder as
     * designed and on Imap folders during error conditions. This allows us
     * to treat Pop3 and Imap the same in this code.
     */
    static boolean verifyOrCreateRemoteSpecialFolder(Account account, String folder, Folder remoteFolder,
            MessagingListener listener, MessagingController controller) throws MessagingException {
        if (folder.equals(account.getTrashFolderName()) ||
                folder.equals(account.getSentFolderName()) ||
                folder.equals(account.getDraftsFolderName())) {
            if (!remoteFolder.exists()) {
                if (!remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
                    for (MessagingListener l : controller.getListeners(listener)) {
                        l.synchronizeMailboxFinished(account, folder, 0, 0);
                    }

                    Timber.i("Done synchronizing folder %s", folder);
                    return false;
                }
            }
        }
        return true;
    }

    static boolean isMessageSuppressed(LocalMessage message, Context context) {
        long messageId = message.getId();
        long folderId = message.getFolder().getId();

        EmailProviderCache cache = EmailProviderCache.getCache(message.getFolder().getAccountUuid(), context);
        return cache.isMessageHidden(messageId, folderId);
    }

    static boolean modeMismatch(Account.FolderMode aMode, Folder.FolderClass fMode) {
        if (aMode == Account.FolderMode.NONE
                || (aMode == Account.FolderMode.FIRST_CLASS &&
                fMode != Folder.FolderClass.FIRST_CLASS)
                || (aMode == Account.FolderMode.FIRST_AND_SECOND_CLASS &&
                fMode != Folder.FolderClass.FIRST_CLASS &&
                fMode != Folder.FolderClass.SECOND_CLASS)
                || (aMode == Account.FolderMode.NOT_SECOND_CLASS &&
                fMode == Folder.FolderClass.SECOND_CLASS)) {
            return true;
        } else {
            return false;
        }
    }

    static void evaluateMessageForDownload(final Message message, final String folderName, final LocalFolder localFolder,
            final Folder remoteFolder, final Account account, final List<Message> unsyncedMessages,
            final List< Message> syncFlagMessages, MessagingController controller) throws MessagingException {
        if (message.isSet(Flag.DELETED)) {
            Timber.v("Message with uid %s is marked as deleted", message.getUid());

            syncFlagMessages.add(message);
            return;
        }

        //TODO: Use an optimized query here without fetching unnecessary message fields
        Message localMessage = localFolder.getMessage(message.getUid());

        if (localMessage == null) {
            if (!message.isSet(Flag.X_DOWNLOADED_FULL) && !message.isSet(Flag.X_DOWNLOADED_PARTIAL)) {
                Timber.v("Message with uid %s has not yet been downloaded", message.getUid());

                unsyncedMessages.add(message);
            } else {
                Timber.v("Message with uid %s is partially or fully downloaded", message.getUid());

                // Store the updated message locally
                localFolder.appendMessages(Collections.singletonList(message));

                localMessage = localFolder.getMessage(message.getUid());

                localMessage.setFlag(Flag.X_DOWNLOADED_FULL, message.isSet(Flag.X_DOWNLOADED_FULL));
                localMessage.setFlag(Flag.X_DOWNLOADED_PARTIAL, message.isSet(Flag.X_DOWNLOADED_PARTIAL));

                for (MessagingListener l : controller.getListeners()) {
                    if (!localMessage.isSet(Flag.SEEN)) {
                        l.synchronizeMailboxNewMessage(account, folderName, localMessage);
                    }
                }
            }
        } else if (!localMessage.isSet(Flag.DELETED)) {
            Timber.v("Message with uid %s is present in the local store", message.getUid());

            if (!localMessage.isSet(Flag.X_DOWNLOADED_FULL) && !localMessage.isSet(Flag.X_DOWNLOADED_PARTIAL)) {
                Timber.v("Message with uid %s is not downloaded, even partially; trying again", message.getUid());

                unsyncedMessages.add(message);
            } else {
                String newPushState = remoteFolder.getNewPushState(localFolder.getPushState(), message);
                if (newPushState != null) {
                    localFolder.setPushState(newPushState);
                }
                syncFlagMessages.add(message);
            }
        } else {
            Timber.v("Local copy of message with uid %s is marked as deleted", message.getUid());
        }
    }

    static boolean shouldNotifyForMessage(Account account, LocalFolder localFolder, Message message, Contacts contacts) {
        // If we don't even have an account name, don't show the notification.
        // (This happens during initial account setup)
        if (account.getName() == null) {
            return false;
        }

        // Do not notify if the user does not have notifications enabled or if the message has
        // been read.
        if (!account.isNotifyNewMail() || message.isSet(Flag.SEEN)) {
            return false;
        }

        Account.FolderMode aDisplayMode = account.getFolderDisplayMode();
        Account.FolderMode aNotifyMode = account.getFolderNotifyNewMailMode();
        Folder.FolderClass fDisplayClass = localFolder.getDisplayClass();
        Folder.FolderClass fNotifyClass = localFolder.getNotifyClass();

        if (SyncUtils.modeMismatch(aDisplayMode, fDisplayClass)) {
            // Never notify a folder that isn't displayed
            return false;
        }

        if (SyncUtils.modeMismatch(aNotifyMode, fNotifyClass)) {
            // Do not notify folders in the wrong class
            return false;
        }

        // If the account is a POP3 account and the message is older than the oldest message we've
        // previously seen, then don't notify about it.
        if (account.getStoreUri().startsWith("pop3") &&
                message.olderThan(new Date(account.getLatestOldMessageSeenTime()))) {
            return false;
        }

        // No notification for new messages in Trash, Drafts, Spam or Sent folder.
        // But do notify if it's the INBOX (see issue 1817).
        Folder folder = message.getFolder();
        if (folder != null) {
            String folderName = folder.getName();
            if (!account.getInboxFolderName().equals(folderName) &&
                    (account.getTrashFolderName().equals(folderName)
                            || account.getDraftsFolderName().equals(folderName)
                            || account.getSpamFolderName().equals(folderName)
                            || account.getSentFolderName().equals(folderName))) {
                return false;
            }
        }

        if (message.getUid() != null && localFolder.getLastUid() != null) {
            try {
                Integer messageUid = Integer.parseInt(message.getUid());
                if (messageUid <= localFolder.getLastUid()) {
                    Timber.d("Message uid is %s, max message uid is %s. Skipping notification.",
                            messageUid, localFolder.getLastUid());
                    return false;
                }
            } catch (NumberFormatException e) {
                // Nothing to be done here.
            }
        }

        // Don't notify if the sender address matches one of our identities and the user chose not
        // to be notified for such messages.
        if (account.isAnIdentity(message.getFrom()) && !account.isNotifySelfNewMail()) {
            return false;
        }

        if (account.isNotifyContactsMailOnly() && !contacts.isAnyInContacts(message.getFrom())) {
            return false;
        }

        return true;
    }
}
