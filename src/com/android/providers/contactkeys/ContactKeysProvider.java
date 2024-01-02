/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.contactkeys;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.ContactKeysManager;
import android.provider.ContactKeysManager.ContactKeys;
import android.provider.ContactsContract;
import android.provider.Flags;

import com.android.internal.annotations.VisibleForTesting;
import com.android.providers.contactkeys.util.ContactKeysPermissions;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides access to a database of contact keys.
 */
@FlaggedApi(Flags.FLAG_USER_KEYS)
public class ContactKeysProvider extends ContentProvider {

    /** The authority for the contact keys provider. */
    public static final String AUTHORITY = "com.android.contactkeys.contactkeysprovider";

    private static final String READ_PERMISSION = "android.permission.READ_CONTACTS";
    private static final String WRITE_PERMISSION = "android.permission.WRITE_CONTACTS";

    /** Special timeUpdated value used when this data is stripped. */
    private static final long STRIPPED_TIME_UPDATED = -1;

    @VisibleForTesting
    ContactKeysDatabaseHelper mDbHelper;

    @Override
    public boolean onCreate() {
        mDbHelper = new ContactKeysDatabaseHelper(getContext());
        return true;
    }

    @Override
    public Bundle call(@NonNull String method, String arg, Bundle extras) {
        switch (method) {
            case ContactKeys.UPDATE_OR_INSERT_CONTACT_KEY_METHOD -> {
                return updateOrInsertContactKey(extras);
            }
            case ContactKeys.GET_CONTACT_KEY_METHOD -> {
                return getContactKey(extras);
            }
            case ContactKeys.GET_ALL_CONTACT_KEYS_METHOD -> {
                return getAllContactKeys(extras);
            }
            case ContactKeys.GET_OWNER_CONTACT_KEYS_METHOD -> {
                return getOwnerContactKeys(extras);
            }
            case ContactKeys.UPDATE_CONTACT_KEY_LOCAL_VERIFICATION_STATE_METHOD -> {
                return updateContactKeyLocalVerificationState(extras);
            }
            case ContactKeys.UPDATE_CONTACT_KEY_REMOTE_VERIFICATION_STATE_METHOD -> {
                return updateContactKeyRemoteVerificationState(extras);
            }
            case ContactKeys.REMOVE_CONTACT_KEY_METHOD -> {
                return removeContactKey(extras);
            }
            case ContactKeys.UPDATE_OR_INSERT_SELF_KEY_METHOD -> {
                return updateOrInsertSelfKey(extras);
            }
            case ContactKeys.UPDATE_SELF_KEY_REMOTE_VERIFICATION_STATE_METHOD -> {
                return updateSelfKeyRemoteVerificationState(extras);
            }
            case ContactKeys.GET_SELF_KEY_METHOD -> {
                return getSelfKey(extras);
            }
            case ContactKeys.GET_ALL_SELF_KEYS_METHOD -> {
                return getAllSelfKeys();
            }
            case ContactKeys.GET_OWNER_SELF_KEYS_METHOD -> {
                return getOwnerSelfKeys();
            }
            case ContactKeys.REMOVE_SELF_KEY_METHOD -> {
                return removeSelfKey(extras);
            }
        }
        throw new IllegalArgumentException("Unexpected method " + method);
    }

    /**
     * Inserts a new entry into the contact keys table or updates one if it already exists.
     *
     * @return Bundle with a {@link ContactKeys.KEY_UPDATED_ROWS} as a key and a
     * boolean value being true if the row was added or updated, false otherwise.
     */
    private Bundle updateOrInsertContactKey(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), WRITE_PERMISSION);

        final Bundle response = new Bundle();

        final String lookupKey = extras.getString(ContactKeys.LOOKUP_KEY);
        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        final byte[] keyValue = extras.getByteArray(ContactKeys.KEY_VALUE);

        Bundle contactInfo = getContactBasicInfoFromCP2(lookupKey);
        String displayName = contactInfo.getString(ContactKeys.DISPLAY_NAME);
        String number = contactInfo.getString(ContactKeys.PHONE_NUMBER);
        String emailAddress = contactInfo.getString(ContactKeys.EMAIL_ADDRESS);

        String callerPackageName = getCallingPackage();
        long timeUpdated = System.currentTimeMillis();

        int rowsUpdateOrInserted = mDbHelper.updateOrInsertContactKey(lookupKey, keyValue,
                deviceId, accountId, callerPackageName, timeUpdated, displayName, number,
                emailAddress);

        response.putBoolean(ContactKeys.KEY_UPDATED_ROWS, rowsUpdateOrInserted > 0);

        return response;
    }

    /**
     * Retrieves a contact key entry given the lookupKey, deviceId and the inferred package name of
     * the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_CONTACT_KEY} as a key and a
     * parcelable {@link ContactKeysManager.ContactKey} as a value
     */
    private Bundle getContactKey(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), READ_PERMISSION);

        final String lookupKey = extras.getString(ContactKeys.LOOKUP_KEY);
        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        String callerPackageName = getCallingPackage();

        ContactKeysManager.ContactKey contactKey = mDbHelper.getContactKey(lookupKey,
                callerPackageName, deviceId, accountId);

        final Bundle response = new Bundle();
        response.putParcelable(ContactKeys.KEY_CONTACT_KEY, contactKey);

        return response;
    }

    /**
     * Retrieves all contact key entries given the lookupKey. All keys that belong to
     * apps visible to the caller will be returned.
     *
     * @return Bundle with a {@link ContactKeys.KEY_CONTACT_KEYS} as a key and
     * a parcelable list of {@link ContactKeysManager.ContactKey} as a value
     */
    private Bundle getAllContactKeys(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), READ_PERMISSION);
        String lookupKey = extras.getString(ContactKeys.LOOKUP_KEY);

        final Bundle response = new Bundle();

        List<ContactKeysManager.ContactKey> contactKeys = mDbHelper.getAllContactKeys(lookupKey);

        contactKeys = filterVisibleContactKeys(contactKeys);
        contactKeys = getStrippedContactKeys(contactKeys);

        response.putParcelableList(ContactKeys.KEY_CONTACT_KEYS, contactKeys);

        return response;
    }

    /**
     * Retrieves all contact key entries given the lookupKey. All keys that belong to the caller
     * will be returned.
     *
     * @return Bundle with a {@link ContactKeys.KEY_CONTACT_KEYS} as a key and
     * a parcelable list of {@link ContactKeysManager.ContactKey} as a value
     */
    private Bundle getOwnerContactKeys(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), READ_PERMISSION);
        String lookupKey = extras.getString(ContactKeys.LOOKUP_KEY);

        final Bundle response = new Bundle();

        String callerPackageName = getCallingPackage();
        List<ContactKeysManager.ContactKey> contactKeys =
                mDbHelper.getContactKeysForOwnerPackageName(lookupKey, callerPackageName);

        response.putParcelableList(ContactKeys.KEY_CONTACT_KEYS, contactKeys);

        return response;
    }

    /**
     * Updates a contact key entry {@link ContactKeys.LOCAL_VERIFICATION_STATE}
     * given the lookupKey, deviceId, accountId and the inferred package name of the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_UPDATED_ROWS} as a key and
     * a boolean value being true if the row was updated, false otherwise.
     */
    private Bundle updateContactKeyLocalVerificationState(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), WRITE_PERMISSION);

        final String lookupKey = extras.getString(ContactKeys.LOOKUP_KEY);
        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        final int localVerificationState =
                extras.getInt(ContactKeys.LOCAL_VERIFICATION_STATE);
        final long timeUpdated = System.currentTimeMillis();
        String callerPackageName = getCallingPackage();

        int rowsUpdated = mDbHelper.updateContactKeyLocalVerificationState(lookupKey,
                callerPackageName, deviceId, accountId, localVerificationState, timeUpdated);

        final Bundle response = new Bundle();
        response.putBoolean(ContactKeys.KEY_UPDATED_ROWS, rowsUpdated > 0);
        return response;
    }

    /**
     * Updates a contact key entry {@link ContactKeys.REMOTE_VERIFICATION_STATE}
     * given the lookupKey, deviceId, accountId and the inferred package name of the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_UPDATED_ROWS} as a key and
     * a boolean value being true if the row was updated, false otherwise.
     */
    private Bundle updateContactKeyRemoteVerificationState(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), WRITE_PERMISSION);

        final String lookupKey = extras.getString(ContactKeys.LOOKUP_KEY);
        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        final int remoteVerificationState =
                extras.getInt(ContactKeys.REMOTE_VERIFICATION_STATE);
        final long timeUpdated = System.currentTimeMillis();
        String callerPackageName = getCallingPackage();

        int rowsUpdated = mDbHelper.updateContactKeyRemoteVerificationState(lookupKey,
                callerPackageName, deviceId, accountId, remoteVerificationState, timeUpdated);

        final Bundle response = new Bundle();
        response.putBoolean(ContactKeys.KEY_UPDATED_ROWS, rowsUpdated > 0);
        return response;
    }

    /**
     * Removes a contact key entry given the lookupKey, deviceId, accountId and
     * the inferred package name of the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_UPDATED_ROWS} as a key and
     * a boolean value being true if the row was removed, false otherwise.
     */
    private Bundle removeContactKey(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), WRITE_PERMISSION);

        final String lookupKey = extras.getString(ContactKeys.LOOKUP_KEY);
        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        String callerPackageName = getCallingPackage();

        int rowsRemoved = mDbHelper.removeContactKey(lookupKey, deviceId, accountId,
                callerPackageName);

        final Bundle response = new Bundle();
        response.putBoolean(ContactKeys.KEY_UPDATED_ROWS, rowsRemoved > 0);
        return response;
    }

    /**
     * Inserts a new entry into the self keys table or updates one if it already exists.
     *
     * @return Bundle with a {@link ContactKeys.KEY_UPDATED_ROWS} as a key and
     * a boolean value being true if the row was added or updated, false otherwise.
     */
    private Bundle updateOrInsertSelfKey(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), WRITE_PERMISSION);

        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        final byte[] keyValue = extras.getByteArray(ContactKeys.KEY_VALUE);

        String callerPackageName = getCallingPackage();
        long timeUpdated = System.currentTimeMillis();

        int rowsUpdateOrInserted = mDbHelper.updateOrInsertSelfKey(keyValue, deviceId, accountId,
                callerPackageName, timeUpdated);

        final Bundle response = new Bundle();
        response.putBoolean(ContactKeys.KEY_UPDATED_ROWS,
                rowsUpdateOrInserted > 0);
        return response;
    }

    /**
     * Updates a self key entry {@link ContactKeys.REMOTE_VERIFICATION_STATE}
     * given the deviceId, accountId and the inferred package name of the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_UPDATED_ROWS} as a key and
     * a boolean value being true if the row was updated, false otherwise.
     */
    private Bundle updateSelfKeyRemoteVerificationState(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), WRITE_PERMISSION);

        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        final int remoteVerificationState =
                extras.getInt(ContactKeys.REMOTE_VERIFICATION_STATE);
        final long timeUpdated = System.currentTimeMillis();
        String callerPackageName = getCallingPackage();

        int rowsUpdated = mDbHelper.updateSelfKeyRemoteVerificationState(callerPackageName,
                deviceId, accountId, remoteVerificationState, timeUpdated);

        final Bundle response = new Bundle();
        response.putBoolean(ContactKeys.KEY_UPDATED_ROWS, rowsUpdated > 0);
        return response;
    }

    /**
     * Retrieves a self key entry given the deviceId, accountId and the inferred package name
     * of the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_CONTACT_KEY} as a key and a
     * parcelable {@link ContactKeysManager.ContactKey} as a value
     */
    private Bundle getSelfKey(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), READ_PERMISSION);
        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        String callerPackageName = getCallingPackage();

        ContactKeysManager.SelfKey selfKey = mDbHelper.getSelfKey(deviceId, accountId,
                callerPackageName);

        final Bundle response = new Bundle();
        response.putParcelable(ContactKeys.KEY_CONTACT_KEY, selfKey);

        return response;
    }

    /**
     * Retrieves all self key entries that belong to apps visible to the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_CONTACT_KEYS} as a key and
     * a parcelable list of {@link ContactKeysManager.SelfKey} as a value
     */
    private Bundle getAllSelfKeys() {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), READ_PERMISSION);

        final Bundle response = new Bundle();

        List<ContactKeysManager.SelfKey> selfKeys = mDbHelper.getAllSelfKeys();

        selfKeys = filterVisibleSelfKeys(selfKeys);
        selfKeys = getStrippedSelfKeys(selfKeys);

        response.putParcelableList(ContactKeys.KEY_CONTACT_KEYS, selfKeys);

        return response;
    }

    /**
     * Retrieves all self key entries that are owned by the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_CONTACT_KEYS} as a key and
     * a parcelable list of {@link ContactKeysManager.ContactKey} as a value
     */
    private Bundle getOwnerSelfKeys() {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), READ_PERMISSION);

        final Bundle response = new Bundle();

        String callerPackageName = getCallingPackage();
        List<ContactKeysManager.SelfKey> selfKeys =
                mDbHelper.getSelfKeysForOwnerPackageName(callerPackageName);

        response.putParcelableList(ContactKeys.KEY_CONTACT_KEYS, selfKeys);

        return response;
    }

    /**
     * Removes a self key entry {@link ContactKeys.REMOTE_VERIFICATION_STATE}
     * given the deviceId and the inferred package name of the caller.
     *
     * @return Bundle with a {@link ContactKeys.KEY_UPDATED_ROWS} as a key and a
     * boolean value being true if the row was removed, false otherwise.
     */
    private Bundle removeSelfKey(Bundle extras) {
        ContactKeysPermissions.enforceCallingOrSelfPermission(getContext(), WRITE_PERMISSION);

        final String deviceId = extras.getString(ContactKeys.DEVICE_ID);
        final String accountId = extras.getString(ContactKeys.ACCOUNT_ID);
        String callerPackageName = getCallingPackage();

        int rowsRemoved = mDbHelper.removeSelfKey(deviceId, accountId, callerPackageName);

        final Bundle response = new Bundle();

        response.putBoolean(ContactKeys.KEY_UPDATED_ROWS, rowsRemoved > 0);

        return response;
    }

    /**
     * Retrieves contact basic info - given the lookupKey of the contact, returns display name,
     * phone number and email address.
     *
     * @return Bundle with a {@link ContactKeys.DISPLAY_NAME},
     * {@link ContactKeys.PHONE_NUMBER}
     * and {@link ContactKeys.EMAIL_ADDRESS} as keys and their corresponding
     * values.
     */
    private Bundle getContactBasicInfoFromCP2(String lookupKey) {
        Bundle bundle = new Bundle();
        final long identity = Binder.clearCallingIdentity();
        try {
            Bundle nameAndNumberBundle = getDisplayNameAndNumber(lookupKey);
            bundle.putString(ContactKeys.DISPLAY_NAME,
                    nameAndNumberBundle.getString(ContactKeys.DISPLAY_NAME));
            bundle.putString(ContactKeys.PHONE_NUMBER,
                    nameAndNumberBundle.getString(ContactKeys.PHONE_NUMBER));
            bundle.putString(ContactKeys.EMAIL_ADDRESS, getEmail(lookupKey));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return bundle;
    }

    /**
     * Returns display name and phone number given the lookupKey of the contact.
     *
     * @return Bundle with a {@link ContactKeys.DISPLAY_NAME}
     * and {@link ContactKeys.PHONE_NUMBER} as keys and their corresponding values.
     */
    private Bundle getDisplayNameAndNumber(String lookupKey) {
        Bundle bundle = new Bundle();
        Cursor c = getContext().getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[] {ContactsContract.Data.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.Data.LOOKUP_KEY + " = ?",
                new String[] { lookupKey }, null);
        if (c.moveToFirst()) {
            bundle.putString(ContactKeys.DISPLAY_NAME,
                    c.getString(c.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME)));
            bundle.putString(ContactKeys.PHONE_NUMBER,
                    c.getString(c.getColumnIndexOrThrow(
                            ContactsContract.CommonDataKinds.Phone.NUMBER)));
        }
        c.close();
        return bundle;
    }

    /**
     * Returns email address given the lookupKey of the contact.
     *
     * @return Bundle with a {@link ContactKeys.EMAIL_ADDRESS} as key and an
     * email address as a value.
     */
    private String getEmail(String lookupKey) {
        String email = null;
        Cursor c = getContext().getContentResolver().query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[] { ContactsContract.CommonDataKinds.Email.ADDRESS },
                ContactsContract.Data.LOOKUP_KEY + " = ?",
                new String[] { lookupKey }, null);
        if (c.moveToFirst()) {
            email = c.getString(c.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Email.ADDRESS));
        }
        c.close();

        return email;
    }

    /**
     * Returns contact keys owned by the packages that are visible to the caller.
     * This is verified by checking that the caller package can query the package owning the key.
     */
    private List<ContactKeysManager.ContactKey> filterVisibleContactKeys(
            List<ContactKeysManager.ContactKey> contactKeys) {
        List<ContactKeysManager.ContactKey> visibleContactKeys =
                new ArrayList<>(contactKeys.size());
        PackageManager packageManager = getContext().getPackageManager();
        String callingPackageName = getCallingPackage();
        for (ContactKeysManager.ContactKey contactKey : contactKeys) {
            try {
                String targetPackageName = contactKey.getOwnerPackageName();
                if (packageManager.canPackageQuery(callingPackageName, targetPackageName)) {
                    visibleContactKeys.add(contactKey);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // The caller doesn't have visibility of the owner package
            }
        }
        return visibleContactKeys;
    }

    /**
     * Removes deviceId, timeUpdated, and key value data from contact keys.
     */
    private static List<ContactKeysManager.ContactKey> getStrippedContactKeys(
            List<ContactKeysManager.ContactKey> visibleContactKeys) {
        List<ContactKeysManager.ContactKey> strippedContactKeys = new ArrayList<>(
                visibleContactKeys.size());
        for (ContactKeysManager.ContactKey key : visibleContactKeys) {
            ContactKeysManager.ContactKey strippedContactKey =
                    new ContactKeysManager.ContactKey(null, key.getAccountId(),
                            key.getOwnerPackageName(), STRIPPED_TIME_UPDATED, null,
                            key.getLocalVerificationState(), key.getRemoteVerificationState(),
                            key.getDisplayName(), key.getPhoneNumber(), key.getEmailAddress());
            strippedContactKeys.add(strippedContactKey);
        }
        return strippedContactKeys;
    }

    /**
     * Returns self keys owned by the packages that are visible to the caller.
     * This is verified by checking that the caller package can query the package owning the key.
     */
    private List<ContactKeysManager.SelfKey> filterVisibleSelfKeys(
            List<ContactKeysManager.SelfKey> selfKeys) {
        List<ContactKeysManager.SelfKey> visibleSelfKeys =
                new ArrayList<>(selfKeys.size());
        PackageManager packageManager = getContext().getPackageManager();
        String callingPackageName = getCallingPackage();
        for (ContactKeysManager.SelfKey selfKey : selfKeys) {
            try {
                String targetPackageName = selfKey.getOwnerPackageName();
                if (packageManager.canPackageQuery(callingPackageName, targetPackageName)) {
                    visibleSelfKeys.add(selfKey);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // The caller doesn't have visibility of the owner package
            }
        }
        return visibleSelfKeys;
    }


    /**
     * Removes deviceId, timeUpdated, and key value data from self keys.
     */
    private static List<ContactKeysManager.SelfKey> getStrippedSelfKeys(
            List<ContactKeysManager.SelfKey> visibleSelfKeys) {
        List<ContactKeysManager.SelfKey> strippedSelfKeys = new ArrayList<>(
                visibleSelfKeys.size());
        for (ContactKeysManager.SelfKey key : visibleSelfKeys) {
            ContactKeysManager.SelfKey strippedContactKey =
                    new ContactKeysManager.SelfKey(null, key.getAccountId(),
                            key.getOwnerPackageName(), STRIPPED_TIME_UPDATED, null,
                            key.getRemoteVerificationState());
            strippedSelfKeys.add(strippedContactKey);
        }
        return strippedSelfKeys;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        // Not implemented
        return null;
    }

    @Override
    public String getType(Uri uri) {
        // Not implemented
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        // Not implemented
        return null;
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        // Not implemented
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        // Not implemented
        return 0;
    }
}