package com.fsck.k9.crypto;


import com.fsck.k9.crypto.AutocryptOperations.AutocryptHeader;
import com.fsck.k9.mail.internet.MimeMessage;
import org.junit.Assert;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;


public class AutocryptOperationsHelper {
    private static AutocryptOperations INSTANCE = new AutocryptOperations();

    public static void assertMessageHasAutocryptHeader(
            MimeMessage message, String addr, boolean isPreferEncryptMutual, byte[] keyData) {
        assertTrue(INSTANCE.hasAutocryptHeader(message));
        AutocryptHeader autocryptHeader = INSTANCE.getValidAutocryptHeader(message);

        assertNotNull(autocryptHeader);
        assertEquals(addr, autocryptHeader.addr);
        assertEquals(isPreferEncryptMutual, autocryptHeader.isPreferEncryptMutual);
        assertArrayEquals(keyData, autocryptHeader.keyData);
    }
}
