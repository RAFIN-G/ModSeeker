/*
 * Copyright (C) 2025 ModSeeker
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.modseeker;

/**
 * Centralized constants for the ModSeeker protocol
 */
public final class ProtocolConstants {

    // Prevent instantiation
    private ProtocolConstants() {
    }

    // Plugin Messaging Channel
    public static final String PLUGIN_CHANNEL = "modseeker:modlist";

    // Mod IDs
    public static final String HIDDER_MOD_ID = "hidder";

    // Message Types
    public static final String MSG_HANDSHAKE_REQUEST = "HANDSHAKE_REQUEST";
    public static final String MSG_MODLIST_REQUEST = "MODLIST_REQUEST";
    public static final String MSG_ACKNOWLEDGE_PRESENCE = "ACKNOWLEDGE_PRESENCE";

    // v2.0 Message Types - Layer 1 HMAC Challenge
    public static final String MSG_CHALLENGE = "CHALLENGE";
    public static final String MSG_CHALLENGE_RESPONSE = "CHALLENGE_RESPONSE";
    public static final String MSG_LAUNCHER_INFO = "LAUNCHER_INFO";

    // JSON Keys
    public static final String KEY_MESSAGE_TYPE = "messageType";
    public static final String KEY_SERVER_ID = "serverId";
    public static final String KEY_NONCE = "nonce";
    public static final String KEY_STATUS = "status";
    public static final String KEY_MOD_ID = "modId";

    // v2.0 JSON Keys
    public static final String KEY_HMAC = "hmac";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_LAUNCHER_ID = "launcherId";
    public static final String KEY_LAUNCHER_NAME = "launcherName";
    public static final String KEY_SIGNATURE = "signature";

    // Status Values
    public static final String STATUS_READY = "ready";

    // Version
    public static final String PLUGIN_VERSION = "2.0";
}
