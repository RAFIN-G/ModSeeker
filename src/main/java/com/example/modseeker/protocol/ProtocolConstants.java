// SPDX-FileCopyrightText: 2025 ModSeeker
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.example.modseeker.protocol;




public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    public static final String PLUGIN_CHANNEL = "modseeker:modlist";

    public static final String HIDDER_MOD_ID = "hidder";

    public static final String MSG_HANDSHAKE_REQUEST = "HANDSHAKE_REQUEST";
    public static final String MSG_MODLIST_REQUEST = "MODLIST_REQUEST";
    public static final String MSG_ACKNOWLEDGE_PRESENCE = "ACKNOWLEDGE_PRESENCE";
    public static final String MSG_ANNOUNCE_PRESENCE = "ANNOUNCE_PRESENCE";

    public static final String MSG_CHALLENGE = "CHALLENGE";
    public static final String MSG_CHALLENGE_RESPONSE = "CHALLENGE_RESPONSE";
    public static final String MSG_LAUNCHER_INFO = "LAUNCHER_INFO";

    public static final String KEY_MESSAGE_TYPE = "messageType";
    public static final String KEY_SERVER_ID = "serverId";
    public static final String KEY_NONCE = "nonce";
    public static final String KEY_STATUS = "status";
    public static final String KEY_MOD_ID = "modId";
    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_VERSION = "version";
    public static final String KEY_CAPABILITIES = "capabilities";

    public static final String KEY_SIGNATURE = "signature";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_LAUNCHER_ID = "launcherId";
    public static final String KEY_SERVER_PUBLIC_KEY = "serverPubKey";
    public static final String KEY_ADVANCED = "advanced";

    public static final String STATUS_READY = "ready";

    public static final String PLUGIN_VERSION = "3.0.0";

    public static final String HIDDER_PROTOCOL_VERSION = "3.6";
    public static final String LAYER_1_DOMAIN = "MODSEEKER|3.6|L1|";
    public static final String LAYER_3_DOMAIN = "MODSEEKER|3.6|L3|";

    // Automatically replaced by ModSeeker/Tools/StartCustomBuild.ps1.
    // This is a public verification key; never place private key material here.
    public static final String CLIENT_PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxyVN1eQ1YPy7J4a5QMAeNvVEuF8MM1e6uA2VYAHYdMNwV5vZYHf5F/03k48V3kXvX4bQTvLQWsD3+3mpyFVV5veRbZmrcxuYTj5SViYFEwg3qxcRK0GWv6rNLaDZqIGQTNaTSPxrNJ7Hkz7SG1uzuL0XjZeXf5QooZa14H+juk5TB09FFs1mtqUIf5/RLYeW4zMPXgK5MSBX2UrqFDWNqwLjC0528VKgPcKWlc5UnKrSAfc4EFOJBawbMn+B2jVoHTejAqbEZU5q8DenmVJCZC9lSi9VrYU0hf5ZZBGjQYv2ak04hPeoi51RDZgCzv27QBNZsWrxFWqxVRsvAZz+EwIDAQAB";
}
