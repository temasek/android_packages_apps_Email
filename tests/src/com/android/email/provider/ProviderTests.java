/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.provider;

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.BodyColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcel;
import android.test.ProviderTestCase2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Tests of the Email provider.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.ProviderTests email
 */
public class ProviderTests extends ProviderTestCase2<EmailProvider> {

    EmailProvider mProvider;
    Context mMockContext;

    public ProviderTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test simple account save/retrieve
     */
    public void testAccountSave() {
        Account account1 = ProviderTestUtils.setupAccount("account-save", true, mMockContext);
        long account1Id = account1.mId;

        Account account2 = EmailContent.Account.restoreAccountWithId(mMockContext, account1Id);

        ProviderTestUtils.assertAccountEqual("testAccountSave", account1, account2);
    }

    /**
     * Test simple account save/retrieve with predefined hostauth records
     */
    public void testAccountSaveHostAuth() {
        Account account1 = ProviderTestUtils.setupAccount("account-hostauth", false, mMockContext);
        // add hostauth data, which should be saved the first time
        account1.mHostAuthRecv = ProviderTestUtils.setupHostAuth("account-hostauth-recv", -1, false,
                mMockContext);
        account1.mHostAuthSend = ProviderTestUtils.setupHostAuth("account-hostauth-send", -1, false,
                mMockContext);
        account1.save(mMockContext);
        long account1Id = account1.mId;

        // Confirm account reads back correctly
        Account account1get = EmailContent.Account.restoreAccountWithId(mMockContext, account1Id);
        ProviderTestUtils.assertAccountEqual("testAccountSave", account1, account1get);

        // Confirm hostauth fields can be accessed & read back correctly
        HostAuth hostAuth1get = EmailContent.HostAuth.restoreHostAuthWithId(mMockContext,
                account1get.mHostAuthKeyRecv);
        ProviderTestUtils.assertHostAuthEqual("testAccountSaveHostAuth-recv",
                account1.mHostAuthRecv, hostAuth1get);
        HostAuth hostAuth2get = EmailContent.HostAuth.restoreHostAuthWithId(mMockContext,
                account1get.mHostAuthKeySend);
        ProviderTestUtils.assertHostAuthEqual("testAccountSaveHostAuth-send",
                account1.mHostAuthSend, hostAuth2get);
    }

    /**
     * Simple test of account parceling.  The rather tortuous path is to ensure that the
     * account is really flattened all the way down to a parcel and back.
     */
    public void testAccountParcel() {
        Account account1 = ProviderTestUtils.setupAccount("parcel", false, mMockContext);
        Bundle b = new Bundle();
        b.putParcelable("account", account1);
        Parcel p = Parcel.obtain();
        b.writeToParcel(p, 0);
        p.setDataPosition(0);       // rewind it for reading
        Bundle b2 = new Bundle(Account.class.getClassLoader());
        b2.readFromParcel(p);
        Account account2 = (Account) b2.getParcelable("account");
        p.recycle();

        ProviderTestUtils.assertAccountEqual("testAccountParcel", account1, account2);
    }

    private final static String[] MAILBOX_UNREAD_COUNT_PROJECTION = new String [] {
        MailboxColumns.UNREAD_COUNT
    };
    private final static int MAILBOX_UNREAD_COUNT_COLMUN = 0;

    /**
     * Get the value of the unread count in the mailbox of the account.
     * This can be different from the actual number of unread messages in that mailbox.
     * @param accountId
     * @param mailboxId
     * @return
     */
    private int getUnreadCount(long mailboxId) {
        String text = null;
        Cursor c = null;
        try {
            c = mMockContext.getContentResolver().query(
                    Mailbox.CONTENT_URI,
                    MAILBOX_UNREAD_COUNT_PROJECTION,
                    EmailContent.RECORD_ID + "=?",
                    new String[] { String.valueOf(mailboxId) },
                    null);
            c.moveToFirst();
            text = c.getString(MAILBOX_UNREAD_COUNT_COLMUN);
        } finally {
            c.close();
        }
        return Integer.valueOf(text);
    }

    /**
     * TODO: HostAuth tests
     */

    /**
     * Test simple mailbox save/retrieve
     */
    public void testMailboxSave() {
        Account account1 = ProviderTestUtils.setupAccount("mailbox-save", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true,
                mMockContext);
        long box1Id = box1.mId;

        Mailbox box2 = EmailContent.Mailbox.restoreMailboxWithId(mMockContext, box1Id);

        ProviderTestUtils.assertMailboxEqual("testMailboxSave", box1, box2);
    }

    private static String[] expectedAttachmentNames =
        new String[] {"attachment1.doc", "attachment2.xls", "attachment3"};
    // The lengths need to be kept in ascending order
    private static long[] expectedAttachmentSizes = new long[] {31415L, 97701L, 151213L};

    /*
     * Returns null if the message has no body.
     */
    private Body loadBodyForMessageId(long messageId) {
        Cursor c = null;
        try {
            c = mMockContext.getContentResolver().query(
                    EmailContent.Body.CONTENT_URI,
                    EmailContent.Body.CONTENT_PROJECTION,
                    EmailContent.Body.MESSAGE_KEY + "=?",
                    new String[] {String.valueOf(messageId)},
                    null);
            int numBodies = c.getCount();
            assertTrue("at most one body", numBodies < 2);
            return c.moveToFirst() ? EmailContent.getContent(c, Body.class) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Test simple message save/retrieve
     *
     * TODO: serverId vs. serverIntId
     */
    public void testMessageSave() {
        Account account1 = ProviderTestUtils.setupAccount("message-save", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        // Test a simple message (saved with no body)
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message1get = EmailContent.Message.restoreMessageWithId(mMockContext, message1Id);
        ProviderTestUtils.assertMessageEqual("testMessageSave", message1, message1get);

        // Test a message saved with a body
        // Note that it will read back w/o the text & html so we must extract those
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        String text2 = message2.mText;
        String html2 = message2.mHtml;
        String textReply2 = message2.mTextReply;
        String htmlReply2 = message2.mHtmlReply;
        long sourceKey2 = message2.mSourceKey;
        message2.mText = null;
        message2.mHtml = null;
        message2.mTextReply = null;
        message2.mHtmlReply = null;
        message2.mSourceKey = 0;
        Message message2get = EmailContent.Message.restoreMessageWithId(mMockContext, message2Id);
        ProviderTestUtils.assertMessageEqual("testMessageSave", message2, message2get);

        // Now see if there's a body saved with the right stuff
        Body body2 = loadBodyForMessageId(message2Id);
        assertEquals("body text", text2, body2.mTextContent);
        assertEquals("body html", html2, body2.mHtmlContent);
        assertEquals("reply text", textReply2, body2.mTextReply);
        assertEquals("reply html", htmlReply2, body2.mHtmlReply);
        assertEquals("source key", sourceKey2, body2.mSourceKey);

        // Message with attachments and body
        Message message3 = ProviderTestUtils.setupMessage("message3", account1Id, box1Id, true,
                false, mMockContext);
        ArrayList<Attachment> atts = new ArrayList<Attachment>();
        for (int i = 0; i < 3; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message3.mAttachments = atts;
        message3.save(mMockContext);
        long message3Id = message3.mId;

        // Now check the attachments; there should be three and they should match name and size
        Cursor c = null;
        try {
            // Note that there is NO guarantee of the order of returned records in the general case,
            // so we specifically ask for ordering by size.  The expectedAttachmentSizes array must
            // be kept sorted by size (ascending) for this test to work properly
            c = mMockContext.getContentResolver().query(
                    Attachment.CONTENT_URI,
                    Attachment.CONTENT_PROJECTION,
                    Attachment.MESSAGE_KEY + "=?",
                    new String[] {
                            String.valueOf(message3Id)
                    },
                    Attachment.SIZE);
            int numAtts = c.getCount();
            assertEquals(3, numAtts);
            int i = 0;
            while (c.moveToNext()) {
                Attachment actual = EmailContent.getContent(c, Attachment.class);
                ProviderTestUtils.assertAttachmentEqual("save-message3", atts.get(i), actual);
                i++;
            }
        } finally {
            c.close();
        }

        // Message with attachments but no body
        Message message4 = ProviderTestUtils.setupMessage("message4", account1Id, box1Id, false,
                false, mMockContext);
        atts = new ArrayList<Attachment>();
        for (int i = 0; i < 3; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message4.mAttachments = atts;
        message4.save(mMockContext);
        long message4Id = message4.mId;

        // Now check the attachments; there should be three and they should match name and size
        c = null;

        try {
            // Note that there is NO guarantee of the order of returned records in the general case,
            // so we specifically ask for ordering by size.  The expectedAttachmentSizes array must
            // be kept sorted by size (ascending) for this test to work properly
            c = mMockContext.getContentResolver().query(
                    Attachment.CONTENT_URI,
                    Attachment.CONTENT_PROJECTION,
                    Attachment.MESSAGE_KEY + "=?",
                    new String[] {
                            String.valueOf(message4Id)
                    },
                    Attachment.SIZE);
            int numAtts = c.getCount();
            assertEquals(3, numAtts);
            int i = 0;
            while (c.moveToNext()) {
                Attachment actual = EmailContent.getContent(c, Attachment.class);
                ProviderTestUtils.assertAttachmentEqual("save-message4", atts.get(i), actual);
                i++;
            }
        } finally {
            c.close();
        }

        // test EmailContent.restoreAttachmentsWitdMessageId()
        Attachment[] attachments =
            Attachment.restoreAttachmentsWithMessageId(mMockContext, message4Id);
        int size = attachments.length;
        assertEquals(3, size);
        for (int i = 0; i < size; ++i) {
            ProviderTestUtils.assertAttachmentEqual("save-message4", atts.get(i), attachments[i]);
        }
    }

    /**
     * TODO: update account
     */

    /**
     * TODO: update mailbox
     */

    /**
     * TODO: update message
     */

    /**
     * Test delete account
     * TODO: hostauth
     */
    public void testAccountDelete() {
        Account account1 = ProviderTestUtils.setupAccount("account-delete-1", true, mMockContext);
        long account1Id = account1.mId;
        Account account2 = ProviderTestUtils.setupAccount("account-delete-2", true, mMockContext);
        long account2Id = account2.mId;

        // make sure there are two accounts
        int numBoxes = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(2, numBoxes);

        // now delete one of them
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, account1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one account now
        numBoxes = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(1, numBoxes);

        // now delete the other one
        uri = ContentUris.withAppendedId(Account.CONTENT_URI, account2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no accounts now
        numBoxes = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(0, numBoxes);
    }

    /**
     * Test for Body.lookupBodyIdWithMessageId()
     * Verifies that:
     * - for a message without body, -1 is returned.
     * - for a mesage with body, the id matches the one from loadBodyForMessageId.
     */
    public void testLookupBodyIdWithMessageId() {
        final ContentResolver resolver = mMockContext.getContentResolver();
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        // 1. create message with no body, check that returned bodyId is -1
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        long bodyId1 = Body.lookupBodyIdWithMessageId(resolver, message1Id);
        assertEquals(bodyId1, -1);

        // 2. create message with body, check that returned bodyId is correct
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        long bodyId2 = Body.lookupBodyIdWithMessageId(resolver, message2Id);
        Body body = loadBodyForMessageId(message2Id);
        assertNotNull(body);
        assertEquals(body.mId, bodyId2);
    }

    /**
     * Test for Body.updateBodyWithMessageId().
     * 1. - create message without body,
     *    - update its body (set TEXT_CONTENT)
     *    - check correct updated body is read back
     *
     * 2. - create message with body,
     *    - update body (set TEXT_CONTENT)
     *    - check correct updated body is read back
     */
    public void testUpdateBodyWithMessageId() {
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        final String textContent = "foobar some odd text";
        final String htmlContent = "and some html";
        final String textReply = "plain text reply";
        final String htmlReply = "or the html reply";

        ContentValues values = new ContentValues();
        values.put(BodyColumns.TEXT_CONTENT, textContent);
        values.put(BodyColumns.HTML_CONTENT, htmlContent);
        values.put(BodyColumns.TEXT_REPLY, textReply);
        values.put(BodyColumns.HTML_REPLY, htmlReply);
        values.put(BodyColumns.SOURCE_MESSAGE_KEY, 17);

        // 1
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Body body1 = loadBodyForMessageId(message1Id);
        assertNull(body1);
        Body.updateBodyWithMessageId(mMockContext, message1Id, values);
        body1 = loadBodyForMessageId(message1Id);
        assertNotNull(body1);
        assertEquals(body1.mTextContent, textContent);
        assertEquals(body1.mHtmlContent, htmlContent);
        assertEquals(body1.mTextReply, textReply);
        assertEquals(body1.mHtmlReply, htmlReply);
        assertEquals(body1.mSourceKey, 17);

        // 2
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        Body body2 = loadBodyForMessageId(message2Id);
        assertNotNull(body2);
        assertTrue(!body2.mTextContent.equals(textContent));
        Body.updateBodyWithMessageId(mMockContext, message2Id, values);
        body2 = loadBodyForMessageId(message1Id);
        assertNotNull(body2);
        assertEquals(body2.mTextContent, textContent);
        assertEquals(body2.mHtmlContent, htmlContent);
        assertEquals(body2.mTextReply, textReply);
        assertEquals(body2.mHtmlReply, htmlReply);
        assertEquals(body2.mSourceKey, 17);
    }

    /**
     * Test delete body.
     * 1. create message without body (message id 1)
     * 2. create message with body (message id 2. The body has _id 1 and messageKey 2).
     * 3. delete first message.
     * 4. verify that body for message 2 has not been deleted.
     * 5. delete message 2, verify body is deleted.
     */
    public void testDeleteBody() {
        final ContentResolver resolver = mMockContext.getContentResolver();

        // Create account and mailboxes
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;

        // 1. create message without body
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;

        // 2. create message with body
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        // verify body is there
        assertNotNull(loadBodyForMessageId(message2Id));

        // 3. delete first message
        resolver.delete(ContentUris.withAppendedId(Message.CONTENT_URI, message1Id), null, null);

        // 4. verify body for second message wasn't deleted
        assertNotNull(loadBodyForMessageId(message2Id));

        // 5. delete second message, check its body is deleted
        resolver.delete(ContentUris.withAppendedId(Message.CONTENT_URI, message2Id), null, null);
        assertNull(loadBodyForMessageId(message2Id));
    }

    /**
     * Test delete orphan bodies.
     * 1. create message without body (message id 1)
     * 2. create message with body (message id 2. Body has _id 1 and messageKey 2).
     * 3. delete first message.
     * 4. delete some other mailbox -- this triggers delete orphan bodies.
     * 5. verify that body for message 2 has not been deleted.
     */
    public void testDeleteOrphanBodies() {
        final ContentResolver resolver = mMockContext.getContentResolver();

        // Create account and twa mailboxes
        Account account1 = ProviderTestUtils.setupAccount("orphaned body", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, true, mMockContext);
        long box2Id = box2.mId;

        // 1. create message without body
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;

        // 2. create message with body
        Message message2 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                true, mMockContext);
        long message2Id = message2.mId;
        //verify body is there
        assertNotNull(loadBodyForMessageId(message2Id));

        // 3. delete first message
        resolver.delete(ContentUris.withAppendedId(Message.CONTENT_URI, message1Id), null, null);

        // 4. delete some mailbox (because it triggers "delete orphan bodies")
        resolver.delete(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box2Id), null, null);

        // 5. verify body for second message wasn't deleted during "delete orphan bodies"
        assertNotNull(loadBodyForMessageId(message2Id));
    }

    /**
     * Test delete mailbox
     */
    public void testMailboxDelete() {
        Account account1 = ProviderTestUtils.setupAccount("mailbox-delete", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, true, mMockContext);
        long box2Id = box2.mId;

        String selection = EmailContent.MailboxColumns.ACCOUNT_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id) };

        // make sure there are two mailboxes
        int numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(2, numBoxes);

        // now delete one of them
        Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one mailbox now
        numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(1, numBoxes);

        // now delete the other one
        uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no mailboxes now
        numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(0, numBoxes);
    }

    /**
     * Test delete message
     * TODO: body
     * TODO: attachments
     */
    public void testMessageDelete() {
        Account account1 = ProviderTestUtils.setupAccount("message-delete", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false,
                true, mMockContext);
        long message2Id = message2.mId;

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND " +
                EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id), String.valueOf(box1Id) };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);

        // now delete one of them
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one message now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(1, numMessages);

        // now delete the other one
        uri = ContentUris.withAppendedId(Message.CONTENT_URI, message2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no messages now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(0, numMessages);
    }

    /**
     * Test delete synced message
     * TODO: body
     * TODO: attachments
     */
    public void testSyncedMessageDelete() {
        Account account1 = ProviderTestUtils.setupAccount("synced-message-delete", true,
                mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false,
                true, mMockContext);
        long message2Id = message2.mId;

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND "
                + EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] {
            String.valueOf(account1Id), String.valueOf(box1Id)
        };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);

        // make sure we start with no synced deletions
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);

        // now delete one of them SYNCED
        Uri uri = ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there's only one message now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(1, numMessages);

        // make sure there's one synced deletion now
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);

        // now delete the other one NOT SYNCED
        uri = ContentUris.withAppendedId(Message.CONTENT_URI, message2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no messages now
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(0, numMessages);

        // make sure there's still one deletion now
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);
    }

    /**
     * Test message update
     * TODO: body
     * TODO: attachments
     */
    public void testMessageUpdate() {
        Account account1 = ProviderTestUtils.setupAccount("message-update", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mMockContext);
        long message1Id = message1.mId;
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false,
                true, mMockContext);
        long message2Id = message2.mId;
        ContentResolver cr = mMockContext.getContentResolver();

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND "
                + EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] {
            String.valueOf(account1Id), String.valueOf(box1Id)
        };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);

        // change the first one
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1Id);
        ContentValues cv = new ContentValues();
        cv.put(MessageColumns.FROM_LIST, "from-list");
        cr.update(uri, cv, null, null);

        // make sure there's no updated message
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);

        // get the message back from the provider, make sure the change "stuck"
        Message restoredMessage = Message.restoreMessageWithId(mMockContext, message1Id);
        assertEquals("from-list", restoredMessage.mFrom);

        // change the second one
        uri = ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message2Id);
        cv = new ContentValues();
        cv.put(MessageColumns.FROM_LIST, "from-list");
        cr.update(uri, cv, null, null);

        // make sure there's one updated message
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);

        // get the message back from the provider, make sure the change "stuck",
        // as before
        restoredMessage = Message.restoreMessageWithId(mMockContext, message2Id);
        assertEquals("from-list", restoredMessage.mFrom);

        // get the original message back from the provider
        Cursor c = cr.query(Message.UPDATED_CONTENT_URI, Message.CONTENT_PROJECTION, null, null,
                null);
        try {
            assertTrue(c.moveToFirst());
            Message originalMessage = EmailContent.getContent(c, Message.class);
            // make sure this has the original value
            assertEquals("from message2", originalMessage.mFrom);
            // Should only be one
            assertFalse(c.moveToNext());
        } finally {
            c.close();
        }

        // delete the second message
        cr.delete(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, message2Id), null, null);

        // hey, presto! the change should be gone
        numMessages = EmailContent.count(mMockContext, Message.UPDATED_CONTENT_URI, selection,
                selArgs);
        assertEquals(0, numMessages);

        // and there should now be a deleted record
        numMessages = EmailContent.count(mMockContext, Message.DELETED_CONTENT_URI, selection,
                selArgs);
        assertEquals(1, numMessages);
    }

    /**
     * TODO: cascaded delete account
     * TODO: hostauth
     * TODO: body
     * TODO: attachments
     * TODO: create other account, mailbox & messages and confirm the right objects were deleted
     */
    public void testCascadeDeleteAccount() {
        Account account1 = ProviderTestUtils.setupAccount("account-delete-cascade", true,
                mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        /* Message message1 = */ ProviderTestUtils.setupMessage("message1", account1Id, box1Id,
                false, true, mMockContext);
        /* Message message2 = */ ProviderTestUtils.setupMessage("message2", account1Id, box1Id,
                false, true, mMockContext);

        // make sure there is one account, one mailbox, and two messages
        int numAccounts = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(1, numAccounts);
        int numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, null, null);
        assertEquals(1, numBoxes);
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(2, numMessages);

        // delete the account
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, account1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no accounts, mailboxes, or messages
        numAccounts = EmailContent.count(mMockContext, Account.CONTENT_URI, null, null);
        assertEquals(0, numAccounts);
        numBoxes = EmailContent.count(mMockContext, Mailbox.CONTENT_URI, null, null);
        assertEquals(0, numBoxes);
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, null, null);
        assertEquals(0, numMessages);
    }

    /**
     * Test cascaded delete mailbox
     * TODO: body
     * TODO: attachments
     * TODO: create other mailbox & messages and confirm the right objects were deleted
     */
    public void testCascadeDeleteMailbox() {
        Account account1 = ProviderTestUtils.setupAccount("mailbox-delete-cascade", true,
                mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        /* Message message1 = */ ProviderTestUtils.setupMessage("message1", account1Id, box1Id,
                false, true, mMockContext);
        /* Message message2 = */ ProviderTestUtils.setupMessage("message2", account1Id, box1Id,
                false, true, mMockContext);

        String selection = EmailContent.MessageColumns.ACCOUNT_KEY + "=? AND " +
                EmailContent.MessageColumns.MAILBOX_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id), String.valueOf(box1Id) };

        // make sure there are two messages
        int numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(2, numMessages);

        // now delete the mailbox
        Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // there should now be zero messages
        numMessages = EmailContent.count(mMockContext, Message.CONTENT_URI, selection, selArgs);
        assertEquals(0, numMessages);
    }

    /**
     * Test cascaded delete message
     * Confirms that deleting a message will also delete its body & attachments
     */
    public void testCascadeMessageDelete() {
        Account account1 = ProviderTestUtils.setupAccount("message-cascade", true, mMockContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mMockContext);
        long box1Id = box1.mId;
        
        // Each message has a body, and also give each 2 attachments
        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, true,
                false, mMockContext);
        ArrayList<Attachment> atts = new ArrayList<Attachment>();
        for (int i = 0; i < 2; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message1.mAttachments = atts;
        message1.save(mMockContext);
        long message1Id = message1.mId;

        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, true,
                false, mMockContext);
        atts = new ArrayList<Attachment>();
        for (int i = 0; i < 2; i++) {
            atts.add(ProviderTestUtils.setupAttachment(
                    -1, expectedAttachmentNames[i], expectedAttachmentSizes[i],
                    false, mMockContext));
        }
        message2.mAttachments = atts;
        message2.save(mMockContext);
        long message2Id = message2.mId;

        // Set up to test total counts of bodies & attachments for our test messages
        String bodySelection = BodyColumns.MESSAGE_KEY + " IN (?,?)";
        String attachmentSelection = AttachmentColumns.MESSAGE_KEY + " IN (?,?)";
        String[] selArgs = new String[] { String.valueOf(message1Id), String.valueOf(message2Id) };
        
        // make sure there are two bodies
        int numBodies = EmailContent.count(mMockContext, Body.CONTENT_URI, bodySelection, selArgs);
        assertEquals(2, numBodies);

        // make sure there are four attachments
        int numAttachments = EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                attachmentSelection, selArgs);
        assertEquals(4, numAttachments);

        // now delete one of the messages
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // there should be one body and two attachments
        numBodies = EmailContent.count(mMockContext, Body.CONTENT_URI, bodySelection, selArgs);
        assertEquals(1, numBodies);

        numAttachments = EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                attachmentSelection, selArgs);
        assertEquals(2, numAttachments);

        // now delete the other message
        uri = ContentUris.withAppendedId(Message.CONTENT_URI, message2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        // make sure there are no bodies or attachments
        numBodies = EmailContent.count(mMockContext, Body.CONTENT_URI, bodySelection, selArgs);
        assertEquals(0, numBodies);

        numAttachments = EmailContent.count(mMockContext, Attachment.CONTENT_URI,
                attachmentSelection, selArgs);
        assertEquals(0, numAttachments);
    }

    /**
     * Test that our unique file name algorithm works as expected.  Since this test requires an
     * SD card, we check the environment first, and return immediately if none is mounted.
     * @throws IOException
     */
    public void testCreateUniqueFile() throws IOException {
        // Delete existing files, if they exist
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return;
        }
        try {
            String fileName = "A11achm3n1.doc";
            File uniqueFile = Attachment.createUniqueFile(fileName);
            assertEquals(fileName, uniqueFile.getName());
            if (uniqueFile.createNewFile()) {
                uniqueFile = Attachment.createUniqueFile(fileName);
                assertEquals("A11achm3n1-2.doc", uniqueFile.getName());
                if (uniqueFile.createNewFile()) {
                    uniqueFile = Attachment.createUniqueFile(fileName);
                    assertEquals("A11achm3n1-3.doc", uniqueFile.getName());
                }
           }
            fileName = "A11achm3n1";
            uniqueFile = Attachment.createUniqueFile(fileName);
            assertEquals(fileName, uniqueFile.getName());
            if (uniqueFile.createNewFile()) {
                uniqueFile = Attachment.createUniqueFile(fileName);
                assertEquals("A11achm3n1-2", uniqueFile.getName());
            }
        } finally {
            File directory = Environment.getExternalStorageDirectory();
            // These are the files that should be created earlier in the test.  Make sure
            // they are deleted for the next go-around
            String[] fileNames = new String[] {"A11achm3n1.doc", "A11achm3n1-2.doc", "A11achm3n1"};
            int length = fileNames.length;
            for (int i = 0; i < length; i++) {
                File file = new File(directory, fileNames[i]);
                if (file.exists()) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Test retrieving attachments by message ID (using EmailContent.Attachment.MESSAGE_ID_URI)
     */
    public void testGetAttachmentByMessageIdUri() {

        // Note, we don't strictly need accounts, mailboxes or messages to run this test.
        Attachment a1 = ProviderTestUtils.setupAttachment(1, "a1", 100, true, mMockContext);
        Attachment a2 = ProviderTestUtils.setupAttachment(1, "a2", 200, true, mMockContext);
        ProviderTestUtils.setupAttachment(2, "a3", 300, true, mMockContext);
        ProviderTestUtils.setupAttachment(2, "a4", 400, true, mMockContext);

        // Now ask for the attachments of message id=1
        // Note: Using the "sort by size" trick to bring them back in expected order
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, 1);
        Cursor c = mMockContext.getContentResolver().query(uri, Attachment.CONTENT_PROJECTION,
                null, null, Attachment.SIZE);
        assertEquals(2, c.getCount());

        try {
            c.moveToFirst();
            Attachment a1Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-1", a1, a1Get);
            c.moveToNext();
            Attachment a2Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-2", a2, a2Get);
        } finally {
            c.close();
        }
    }

    /**
     * Test deleting attachments by message ID (using EmailContent.Attachment.MESSAGE_ID_URI)
     */
    public void testDeleteAttachmentByMessageIdUri() {
        ContentResolver mockResolver = mMockContext.getContentResolver();

        // Note, we don't strictly need accounts, mailboxes or messages to run this test.
        ProviderTestUtils.setupAttachment(1, "a1", 100, true, mMockContext);
        ProviderTestUtils.setupAttachment(1, "a2", 200, true, mMockContext);
        Attachment a3 = ProviderTestUtils.setupAttachment(2, "a3", 300, true, mMockContext);
        Attachment a4 = ProviderTestUtils.setupAttachment(2, "a4", 400, true, mMockContext);

        // Delete all attachments for message id=1
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, 1);
        mockResolver.delete(uri, null, null);

        // Read back all attachments and confirm that we have the expected remaining attachments
        // (the attachments that are set for message id=2).  Note order-by size to simplify test.
        Cursor c = mockResolver.query(Attachment.CONTENT_URI, Attachment.CONTENT_PROJECTION,
                null, null, Attachment.SIZE);
        assertEquals(2, c.getCount());

        try {
            c.moveToFirst();
            Attachment a3Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-3", a3, a3Get);
            c.moveToNext();
            Attachment a4Get = EmailContent.getContent(c, Attachment.class);
            ProviderTestUtils.assertAttachmentEqual("getAttachByUri-4", a4, a4Get);
        } finally {
            c.close();
        }
    }

    /**
     * Tests of default account behavior
     *
     * 1.  Simple set/get
     * 2.  Moving default between 3 accounts
     * 3.  Delete default, make sure another becomes default
     */
    public void testSetGetDefaultAccount() {
        // There should be no default account if there are no accounts
        long defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(-1, defaultAccountId);

        Account account1 = ProviderTestUtils.setupAccount("account-default-1", true, mMockContext);
        long account1Id = account1.mId;
        Account account2 = ProviderTestUtils.setupAccount("account-default-2", true, mMockContext);
        long account2Id = account2.mId;
        Account account3 = ProviderTestUtils.setupAccount("account-default-3", true, mMockContext);
        long account3Id = account3.mId;

        // With three accounts, but none marked default, confirm that some default account
        // is returned.  Which one is undefined here.
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertTrue(defaultAccountId == account1Id
                    || defaultAccountId == account2Id
                    || defaultAccountId == account3Id);

        updateIsDefault(account1, true);
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account1Id, defaultAccountId);

        updateIsDefault(account2, true);
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account2Id, defaultAccountId);

        updateIsDefault(account3, true);
        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account3Id, defaultAccountId);

        // Now delete a non-default account and confirm no change
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, account1Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account3Id, defaultAccountId);

        // Now confirm deleting the default account and it switches to another one
        uri = ContentUris.withAppendedId(Account.CONTENT_URI, account3Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(account2Id, defaultAccountId);

        // Now delete the final account and confirm there are no default accounts again
        uri = ContentUris.withAppendedId(Account.CONTENT_URI, account2Id);
        mMockContext.getContentResolver().delete(uri, null, null);

        defaultAccountId = Account.getDefaultAccountId(mMockContext);
        assertEquals(-1, defaultAccountId);
    }

    private void updateIsDefault(Account account, boolean newState) {
        account.setDefaultAccount(newState);
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.IS_DEFAULT, account.mIsDefault);
        account.update(mMockContext, cv);
    }

    public static Message setupUnreadMessage(String name, long accountId, long mailboxId,
            boolean addBody, boolean saveIt, Context context) {
        Message msg =
            ProviderTestUtils.setupMessage(name, accountId, mailboxId, addBody, false, context);
        msg.mFlagRead = false;
        if (saveIt) {
            msg.save(context);
        }
        return msg;
    }

    public void testUnreadCountTriggers() {
        // Start with one account and three mailboxes
        Account account = ProviderTestUtils.setupAccount("triggers", true, mMockContext);
        Mailbox boxA = ProviderTestUtils.setupMailbox("boxA", account.mId, true, mMockContext);
        Mailbox boxB = ProviderTestUtils.setupMailbox("boxB", account.mId, true, mMockContext);
        Mailbox boxC = ProviderTestUtils.setupMailbox("boxC", account.mId, true, mMockContext);

        // Make sure there are no unreads
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(0, getUnreadCount(boxB.mId));
        assertEquals(0, getUnreadCount(boxC.mId));

        // Create 4 unread messages (only 3 named) in boxA
        Message message1 = setupUnreadMessage("message1", account.mId, boxA.mId,
                false, true, mMockContext);
        Message message2= setupUnreadMessage("message2", account.mId, boxA.mId,
                false, true, mMockContext);
        Message message3 =  setupUnreadMessage("message3", account.mId, boxA.mId,
                false, true, mMockContext);
        setupUnreadMessage("message4", account.mId, boxC.mId, false, true, mMockContext);

        // Make sure the unreads are where we expect them
        assertEquals(3, getUnreadCount(boxA.mId));
        assertEquals(0, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // After deleting message 1, the count in box A should be decremented (to 2)
        ContentResolver cr = mMockContext.getContentResolver();
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, message1.mId);
        cr.delete(uri, null, null);
        assertEquals(2, getUnreadCount(boxA.mId));
        assertEquals(0, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Move message 2 to box B, leaving 1 in box A and 1 in box B
        message2.mMailboxKey = boxB.mId;
        ContentValues cv = new ContentValues();
        cv.put(MessageColumns.MAILBOX_KEY, boxB.mId);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message2.mId), cv, null, null);
        assertEquals(1, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Mark message 3 (from box A) read, leaving 0 in box A
        cv.clear();
        cv.put(MessageColumns.FLAG_READ, 1);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message3.mId), cv, null, null);
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Move message 3 to box C; should be no change (it's read)
        message3.mMailboxKey = boxC.mId;
        cv.clear();
        cv.put(MessageColumns.MAILBOX_KEY, boxC.mId);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message3.mId), cv, null, null);
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(1, getUnreadCount(boxC.mId));

        // Mark message 3 unread; it's now in box C, so that box's count should go up to 3
        cv.clear();
        cv.put(MessageColumns.FLAG_READ, 0);
        cr.update(ContentUris.withAppendedId(Message.CONTENT_URI, message3.mId), cv, null, null);
        assertEquals(0, getUnreadCount(boxA.mId));
        assertEquals(1, getUnreadCount(boxB.mId));
        assertEquals(2, getUnreadCount(boxC.mId));
    }

    /**
     * Test for EmailProvider.createIndex().
     * Check that it returns exacly the same string as the one used previously for index creation.
     */
    public void testCreateIndex() {
        String oldStr = "create index message_" + MessageColumns.TIMESTAMP
            + " on " + Message.TABLE_NAME + " (" + MessageColumns.TIMESTAMP + ");";
        String newStr = EmailProvider.createIndex(Message.TABLE_NAME, MessageColumns.TIMESTAMP);
        assertEquals(newStr, oldStr);
    }

    public void testIdAddToField() {
        ContentResolver cr = mMockContext.getContentResolver();
        ContentValues cv = new ContentValues();

        // Try changing the newMessageCount of an account
        Account account = ProviderTestUtils.setupAccount("field-add", true, mMockContext);
        int startCount = account.mNewMessageCount;
        // "field" and "add" are the two required elements
        cv.put(EmailContent.FIELD_COLUMN_NAME, AccountColumns.NEW_MESSAGE_COUNT);
        cv.put(EmailContent.ADD_COLUMN_NAME, 17);
        cr.update(ContentUris.withAppendedId(Account.ADD_TO_FIELD_URI, account.mId),
                cv, null, null);
        Account restoredAccount = Account.restoreAccountWithId(mMockContext, account.mId);
        assertEquals(17 + startCount, restoredAccount.mNewMessageCount);
        cv.put(EmailContent.ADD_COLUMN_NAME, -11);
        cr.update(ContentUris.withAppendedId(Account.ADD_TO_FIELD_URI, account.mId),
                cv, null, null);
        restoredAccount = Account.restoreAccountWithId(mMockContext, account.mId);
        assertEquals(17 - 11 + startCount, restoredAccount.mNewMessageCount);

        // Now try with a mailbox
        Mailbox boxA = ProviderTestUtils.setupMailbox("boxA", account.mId, true, mMockContext);
        assertEquals(0, boxA.mUnreadCount);
        cv.put(EmailContent.FIELD_COLUMN_NAME, MailboxColumns.UNREAD_COUNT);
        cv.put(EmailContent.ADD_COLUMN_NAME, 11);
        cr.update(ContentUris.withAppendedId(Mailbox.ADD_TO_FIELD_URI, boxA.mId), cv, null, null);
        Mailbox restoredBoxA = Mailbox.restoreMailboxWithId(mMockContext, boxA.mId);
        assertEquals(11, restoredBoxA.mUnreadCount);
    }
}
