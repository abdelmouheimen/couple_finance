package com.couplefinance.shared.pagination;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.couplefinance.shared.error.ApplicationException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encodes the sort position of a listing into an opaque cursor. The position is encrypted and authenticated
 * (AES-256-GCM): the client can neither read internal values nor forge or alter a cursor, and the {@code scope}
 * (typically authenticated user id, household id and listing name) is bound as additional authenticated data, so
 * a cursor replayed by another user or on another listing is rejected exactly like a tampered one. Every failure
 * is a 400 {@code INVALID_CURSOR} that does not reveal the reason.
 *
 * <p>The key is configured by {@link CursorProperties}; cursors stay valid only as long as the key does.
 */
public final class CursorCodec {

    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CURSOR_CHARS = 1024;
    private static final int MAX_VALUES = 8;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CursorCodec(byte[] keyBytes) {
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("The cursor key must be 32 bytes.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * @param scope the caller/listing binding; build it from the authenticated principal, never from request input
     * @param position the values of the ordering columns of the last returned item
     */
    public String encode(String scope, List<String> position) {
        if (position.size() > MAX_VALUES) {
            throw new IllegalArgumentException("A cursor position has at most " + MAX_VALUES + " values.");
        }
        try {
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(plain);
            out.writeByte(VERSION);
            out.writeByte(position.size());
            for (String value : position) {
                out.writeUTF(value);
            }
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain.toByteArray());
            byte[] token = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, token, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, token, IV_BYTES, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cursor encoding failed", e);
        }
    }

    /** @throws ApplicationException {@code INVALID_CURSOR} when the cursor is malformed, tampered or foreign */
    public List<String> decode(String scope, String cursor) {
        if (cursor.length() > MAX_CURSOR_CHARS) {
            throw invalid();
        }
        try {
            byte[] token = Base64.getUrlDecoder().decode(cursor);
            if (token.length <= IV_BYTES) {
                throw invalid();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, token, 0, IV_BYTES));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(token, IV_BYTES, token.length - IV_BYTES);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
            if (in.readUnsignedByte() != VERSION) {
                throw invalid();
            }
            int count = in.readUnsignedByte();
            if (count > MAX_VALUES) {
                throw invalid();
            }
            List<String> position = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                position.add(in.readUTF());
            }
            if (in.available() != 0) {
                throw invalid();
            }
            return List.copyOf(position);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ApplicationException invalid() {
        return new ApplicationException(PaginationErrorCode.INVALID_CURSOR, "The cursor is invalid.");
    }
}
