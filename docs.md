# ValleyAuth Addon Development Guide

<!-- Version badges - update these when releasing -->
![ValleyAuth](https://img.shields.io/badge/ValleyAuth-0.2.0--alpha-blue)
![ValleyCert](https://img.shields.io/badge/ValleyCert-0.2.0--alpha-green)
![Paper](https://img.shields.io/badge/Paper-1.21+-orange)
![Java](https://img.shields.io/badge/Java-21+-red)

This guide teaches you how to create Paper plugins that integrate with the ValleyAuth ecosystem. You will learn to use the ValleyCert library to request certificates from the ValleyAuth Certificate Authority (CA), validate capabilities, encrypt data, and follow security best practices.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Setup](#project-setup)
3. [Initializing ValleyCert](#initializing-valleycert)
4. [Requesting Certificates](#requesting-certificates)
5. [Validating Capabilities](#validating-capabilities)
6. [Encrypting Data](#encrypting-data)
7. [Storing Encrypted Data](#storing-encrypted-data)
8. [Error Handling](#error-handling)
9. [Best Practices](#best-practices)
10. [API Reference](#api-reference)
11. [FAQ](#faq)

---

## Prerequisites

Before you begin, make sure you have the following:

| Requirement | Version | Notes |
|---|---|---|
| Java | 21 or newer | ValleyCert uses modern Java features |
| Paper API | 1.21+ | Your plugin targets the Paper server platform |
| ValleyAuth | 0.2.0-alpha | Must be installed on the server at runtime |
| ValleyCert JAR | 0.2.0-alpha | Library dependency (built separately) |
| Gradle | 8.x | For building your plugin |

### What ValleyCert Is and Is Not

ValleyCert is a **client-side library**. It is NOT a certificate authority. It communicates with ValleyAuth Core, which in turn communicates with the ValleyCertAPI (the actual CA). Your plugin uses ValleyCert to request and manage certificates, but the certificates themselves are issued and signed by the CA.

```
Your Plugin  -->  ValleyCert (library)  -->  ValleyAuth Core  -->  ValleyCertAPI (CA)
```

---

## Project Setup

### Directory Structure

Create a standard Paper plugin project:

```
my-plugin/
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  src/
    main/
      java/
        com/
          example/
            myplugin/
              MyPlugin.java
      resources/
        plugin.yml
```

### settings.gradle.kts

```kotlin
rootProject.name = "my-plugin"
```

### gradle.properties

```properties
group=com.example.myplugin
version=1.0.0
```

### build.gradle.kts

```kotlin
plugins {
    java
}

group = "com.example.myplugin"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(files("../ValleyCert/build/libs/ValleyCert-0.2.0-alpha.jar"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("MyPlugin")
}
```

#### Dependency Explanation

| Dependency | Scope | Why |
|---|---|---|
| `paper-api:1.21.4-R0.1-SNAPSHOT` | `compileOnly` | Paper is provided by the server at runtime. Never bundle it. |
| `ValleyCert-0.2.0-alpha.jar` | `implementation` | ValleyCert is bundled into your JAR since it is a library, not a server plugin. |

The `implementation(files(...))` scope means ValleyCert classes are compiled against AND packaged inside your plugin JAR. This is correct because ValleyCert is a library, not a standalone plugin. Your server must also have the ValleyAuth plugin installed separately.

### Building ValleyCert First

Since ValleyCert is referenced as a local JAR file, you need to build it first:

```bash
cd ValleyCert
./gradlew build
```

The output JAR will be at `ValleyCert/build/libs/ValleyCert-0.2.0-alpha.jar`.

### plugin.yml

```yaml
name: MyPlugin
version: '1.0.0'
main: com.example.myplugin.MyPlugin
api-version: '1.21'
description: My ValleyAuth addon
author: YourName
softdepend:
  - ValleyAuth

commands:
  myplugin:
    description: MyPlugin admin commands
    usage: /myplugin <subcommand>
    permission: myplugin.admin

permissions:
  myplugin.admin:
    description: MyPlugin admin commands
    default: op
  myplugin.use:
    description: Use MyPlugin features
    default: true
```

#### plugin.yml Fields Explained

| Field | Value | Why |
|---|---|---|
| `softdepend` | `ValleyAuth` | ValleyAuth is required at runtime, but using `softdepend` lets your plugin load first and handle the missing dependency gracefully. |
| `api-version` | `1.21` | Matches the Paper API version your plugin targets. |
| `main` | Full qualified class name | Must point to your main class that extends `JavaPlugin`. |

---

## Initializing ValleyCert

The main plugin class is where you initialize ValleyCert. This is the most critical part of your addon because it determines whether your plugin can function at all.

### Complete Main Class

```java
package com.example.myplugin;

import com.valleyrealm.valleycert.ValleyCert;
import com.valleyrealm.valleycert.CertificateData;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    private ValleyCert valleyCert;

    @Override
    public void onEnable() {
        getLogger().info("MyPlugin enabling...");

        // Step 1: Create ValleyCert instance
        valleyCert = new ValleyCert(getDataFolder().toPath());

        // Step 2: Define what your plugin needs
        String pluginId = "my-plugin";
        String[] capabilities = {"VLINK"};
        int requestedLifetimeDays = 90;

        // Step 3: Initialize and request certificate
        boolean success = valleyCert.initialize(pluginId, capabilities, requestedLifetimeDays);

        if (!success) {
            getLogger().severe("Failed to obtain certificate. Plugin will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Step 4: Verify capabilities
        if (!valleyCert.validateCapability("VLINK")) {
            getLogger().warning("Certificate missing VLINK capability.");
        }

        getLogger().info("MyPlugin enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MyPlugin disabled.");
    }

    public ValleyCert getValleyCert() {
        return valleyCert;
    }
}
```

### What Happens During initialize()

When you call `valleyCert.initialize(pluginId, capabilities, requestedLifetimeDays)`, the following steps occur in order:

```
1. Check if ValleyAuth plugin is present on the server
   |
   +-- If not present: log error, set enabled=false, return false
   |
   v
2. Look for cached certificate at <plugin-data>/certs/<pluginId>/valley.cert
   |
   +-- If found and valid: load into memory, schedule renewal, return true
   |
   +-- If found but invalid: continue to step 3
   |
   +-- If not found: continue to step 3
   |
   v
3. Send HTTP POST to ValleyAuth Core at /api/certificate/issue
   |
   +-- If request fails: return false
   |
   v
4. Validate received certificate
   |
   +-- Check status is ACTIVE
   +-- Check not expired
   +-- Check issuer is "ValleyAuth Core"
   +-- Verify ECDSA signature
   +-- Check revocation timestamp is null
   |
   +-- If any check fails: return false
   |
   v
5. Store certificate to disk (atomic write)
   |
   v
6. Schedule background renewal check (hourly)
   |
   v
7. Return true
```

### Constructor Parameters

The `ValleyCert` constructor takes one parameter:

| Parameter | Type | Description |
|---|---|---|
| `pluginDataFolder` | `java.nio.file.Path` | Your plugin's data folder. Pass `getDataFolder().toPath()` from your main class. |

### initialize() Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `pluginId` | `String` | (required) | A unique identifier for your plugin. Used for certificate storage and CA requests. Use lowercase with hyphens (e.g., `"my-plugin"`). |
| `capabilities` | `String[]` | (required) | Array of capability strings your plugin needs. Only request what you actually use. |
| `requestedLifetimeDays` | `int` | 90 | How many days you want the certificate to be valid. Maximum is 120. The CA may issue a shorter certificate. |

---

## Requesting Certificates

Certificate requests happen automatically when you call `initialize()`. However, understanding the request flow helps you debug issues.

### Certificate Request Flow

```
Your Plugin                    ValleyAuth Core                 ValleyCertAPI (CA)
     |                               |                               |
     |  POST /api/certificate/issue  |                               |
     |  {pluginId, caps, days}       |                               |
     |------------------------------>|                               |
     |                               |  POST /api/certificate/issue  |
     |                               |  {pluginId, caps, days}       |
     |                               |------------------------------>|
     |                               |                               |
     |                               |    ECDSA P-256 signing        |
     |                               |    Key generation (if new)    |
     |                               |    Certificate storage        |
     |                               |                               |
     |                               |  Response: Certificate JSON   |
     |                               |<------------------------------|
     |  Response: Certificate JSON   |                               |
     |<------------------------------|                               |
     |                               |                               |
     |  Validate signature           |                               |
     |  Store to disk                |                               |
     |  Schedule renewal             |                               |
```

### What Gets Sent to the CA

The HTTP request body looks like this:

```json
{
    "pluginId": "my-plugin",
    "capabilities": ["VLINK", "IDENTITY_LINK"],
    "requestedValidityDays": 90
}
```

### What You Get Back

The CA responds with a certificate containing:

```json
{
    "certificateId": "550e8400-e29b-41d4-a716-446655440000",
    "pluginId": "my-plugin",
    "capabilities": ["VLINK", "IDENTITY_LINK"],
    "issuanceDate": "2025-01-15T10:30:00Z",
    "expirationDate": "2025-04-15T10:30:00Z",
    "issuer": "ValleyAuth Core",
    "signature": "Base64-encoded ECDSA signature...",
    "publicKey": "Base64-encoded EC public key...",
    "status": "ACTIVE",
    "encryptedRevocationTimestamp": null
}
```

### Requesting Multiple Capabilities

You can request several capabilities in a single certificate request:

```java
String[] capabilities = {
    "VLINK",
    "IDENTITY_LINK",
    "RANK_SHARE",
    "MIGRATION_PROVIDER"
};

boolean success = valleyCert.initialize("my-plugin", capabilities, 90);
```

### Certificate Lifetime

| Lifetime | Behavior |
|---|---|
| Less than 90 days | Accepted, certificate issued for requested duration |
| 90 days | Default, recommended for most plugins |
| 90 to 120 days | Accepted, but may be shorter depending on CA policy |
| More than 120 days | CA caps at 120 days maximum |

---

## Validating Capabilities

Capabilities are string identifiers that control what your plugin is allowed to do. You must check capabilities before performing protected operations.

### Available Capabilities

| Capability | String Value | Description |
|---|---|---|
| VLINK | `"VLINK"` | Allows VLink operations for cross-server identity linking |
| IDENTITY_LINK | `"IDENTITY_LINK"` | Allows linking player identities across services |
| RANK_SHARE | `"RANK_SHARE"` | Allows sharing rank data between servers |
| MIGRATION_PROVIDER | `"MIGRATION_PROVIDER"` | Allows registering as a migration provider |
| MIGRATION_ACCESS | `"MIGRATION_ACCESS"` | Allows accessing protected migration APIs |
| CERTIFICATE_MANAGEMENT | `"CERTIFICATE_MANAGEMENT"` | Allows certificate management operations |

### Single Capability Check

```java
public void performVLinkAction() {
    if (!valleyCert.validateCapability("VLINK")) {
        getLogger().warning("Certificate missing VLINK capability. Cannot perform VLink action.");
        return;
    }

    // Safe to proceed with VLink operation
    CertificateData cert = valleyCert.getCertificate();
    // Use cert for API calls
}
```

### Multiple Capability Check

To check multiple capabilities, call `validateCapability()` for each one:

```java
public void performComplexAction() {
    boolean hasVlink = valleyCert.validateCapability("VLINK");
    boolean hasIdentity = valleyCert.validateCapability("IDENTITY_LINK");

    if (!hasVlink || !hasIdentity) {
        getLogger().warning("Missing required capabilities for complex action.");
        return;
    }

    // Both capabilities present, safe to proceed
}
```

### Checking Capability in a Command

```java
@Override
public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
        sender.sendMessage("This command can only be used by players.");
        return true;
    }

    if (!player.hasPermission("myplugin.admin")) {
        player.sendMessage("You don't have permission.");
        return true;
    }

    if (args.length == 0) {
        sendUsage(player);
        return true;
    }

    switch (args[0].toLowerCase()) {
        case "link" -> handleLink(player);
        case "status" -> handleStatus(player);
        default -> sendUsage(player);
    }

    return true;
}

private void handleLink(Player player) {
    // Check capability before performing the operation
    if (!valleyCert.validateCapability("VLINK")) {
        player.sendMessage("Certificate missing VLINK capability.");
        return;
    }

    player.sendMessage("Starting VLink operation...");
    // Perform the actual VLink logic
}
```

### CertificateData Inspection

You can also inspect the full certificate object:

```java
CertificateData cert = valleyCert.getCertificate();
if (cert != null) {
    getLogger().info("Certificate ID: " + cert.getCertificateId());
    getLogger().info("Plugin ID: " + cert.getPluginId());
    getLogger().info("Status: " + cert.getStatus());
    getLogger().info("Issuer: " + cert.getIssuer());
    getLogger().info("Issued: " + cert.getIssuanceDate());
    getLogger().info("Expires: " + cert.getExpirationDate());
    getLogger().info("Capabilities: " + cert.getCapabilities());
    getLogger().info("Needs renewal: " + cert.needsRenewal());
}
```

### CertificateData Methods

| Method | Returns | Description |
|---|---|---|
| `getPluginId()` | `String` | The plugin that owns this certificate |
| `getCertificateId()` | `String` | UUID identifier for this certificate |
| `getCapabilities()` | `List<String>` | List of granted capabilities |
| `getIssuanceDate()` | `Date` | When the certificate was issued (defensive copy) |
| `getExpirationDate()` | `Date` | When the certificate expires (defensive copy) |
| `getIssuer()` | `String` | Always "ValleyAuth Core" for real certificates |
| `getSignature()` | `String` | Base64-encoded ECDSA signature |
| `getPublicKey()` | `String` | Base64-encoded EC public key |
| `getStatus()` | `CertificateStatus` | ACTIVE, EXPIRED, REVOKED, or SUSPENDED |
| `isCurrentlyValid()` | `boolean` | True if status is ACTIVE and within date range |
| `isExpired()` | `boolean` | True if current time is past expiration date |
| `needsRenewal()` | `boolean` | True if within 7 days of expiry |

---

## Encrypting Data

ValleyCert provides a certificate with an embedded ECDSA public key. You can use this key to encrypt data that only the CA can decrypt. The encryption uses a two-layer approach: AES-GCM for the payload and ECIES for the session key.

### Encryption Architecture

```
                    ENCRYPTION (Client Side)
                    ========================

  Plaintext Data
       |
       v
  +------------------+
  | Generate random  |
  | AES-256 session  |
  | key (32 bytes)   |
  +------------------+
       |
       +--------+
       |        |
       v        v
  +---------+  +------------------+
  | Encrypt |  | Encrypt session  |
  | payload |  | key with CA's    |
  | with    |  | EC public key    |
  | AES-GCM |  | (ECIES)         |
  +---------+  +------------------+
       |              |
       v              v
  +---------+  +------------------+
  | IV +    |  | Encrypted        |
  | Encrypt |  | Session Key      |
  | ed Pay  |  | (Base64)         |
  | load    |  |                  |
  +---------+  +------------------+
       |              |
       v              v
  +--------------------------+
  |   EncryptedContainer     |
  |  {                       |
  |    encryptedSessionKey,  |
  |    iv,                   |
  |    encryptedPayload      |
  |  }                       |
  +--------------------------+
       |
       v
  Store or transmit


                    DECRYPTION (Server Side Only)
                    ==============================

  EncryptedContainer
       |
       v
  +------------------+
  | Decrypt session  |
  | key with CA's    |
  | EC private key   |
  | (ECIES)          |
  +------------------+
       |
       v
  +------------------+
  | Decrypt payload  |
  | with recovered   |
  | AES session key  |
  | (AES-GCM)        |
  +------------------+
       |
       v
  Plaintext Data
```

### Why Two Layers?

| Layer | Algorithm | Purpose |
|---|---|---|
| Payload encryption | AES-256-GCM | Fast, efficient encryption of large data. Provides confidentiality and integrity (authentication tag). |
| Session key encryption | ECIES (EC) | Encrypts the AES session key with the CA's public key. Only the CA (with the private key) can recover the session key. |

This hybrid approach gives you the speed of symmetric encryption for the data and the security of asymmetric encryption for key exchange.

### EncryptedContainer Record

The encryption result is packaged as a Java record:

```java
public record EncryptedContainer(
    String encryptedSessionKey,  // Base64-encoded ECIES-encrypted AES key
    String iv,                   // Base64-encoded 12-byte initialization vector
    String encryptedPayload      // Base64-encoded AES-GCM ciphertext
) {
    public String toString() {
        return encryptedSessionKey + ":" + iv + ":" + encryptedPayload;
    }

    public static EncryptedContainer fromString(String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid encrypted container format");
        }
        return new EncryptedContainer(parts[0], parts[1], parts[2]);
    }
}
```

### CryptoHelper Class

Here is a complete `CryptoHelper` class you can add to your plugin:

```java
package com.example.myplugin;

import com.valleyrealm.valleycert.CertificateData;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class CryptoHelper {

    private final CertificateData certificate;

    public CryptoHelper(CertificateData certificate) {
        this.certificate = certificate;
    }

    /**
     * Encrypt a string payload using AES-GCM with a random session key.
     * The session key is encrypted with the certificate's embedded public key.
     *
     * @param plaintext The data to encrypt
     * @return EncryptedContainer with encrypted session key and payload
     */
    public EncryptedContainer encrypt(String plaintext) throws Exception {
        // 1. Generate random AES-256 session key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey sessionKey = keyGen.generateKey();

        // 2. Generate random 12-byte IV for AES-GCM
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        // 3. Encrypt payload with AES-GCM
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec);
        byte[] encryptedPayload = aesCipher.doFinal(
            plaintext.getBytes(StandardCharsets.UTF_8)
        );

        // 4. Load CA's public key from certificate
        String publicKeyStr = certificate.getPublicKey();
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        PublicKey publicKey = keyFactory.generatePublic(
            new X509EncodedKeySpec(publicKeyBytes)
        );

        // 5. Encrypt session key with CA's public key (ECIES)
        Cipher keyCipher = Cipher.getInstance("ECIES");
        keyCipher.init(Cipher.WRAP_MODE, publicKey);
        byte[] encryptedSessionKey = keyCipher.doFinal(sessionKey.getEncoded());

        // 6. Package everything into EncryptedContainer
        return new EncryptedContainer(
            Base64.getEncoder().encodeToString(encryptedSessionKey),
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(encryptedPayload)
        );
    }

    /**
     * Decrypt is NOT possible client-side.
     * Only the CA has the private key needed for decryption.
     */
    public String decrypt(EncryptedContainer container) throws Exception {
        throw new UnsupportedOperationException(
            "Client-side decryption requires CA private key. " +
            "Use the ValleyCert API /api/certificate/decrypt endpoint instead."
        );
    }

    /**
     * Save encrypted data to a file.
     */
    public void saveEncrypted(File file, EncryptedContainer container) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(container.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Load encrypted data from a file.
     */
    public EncryptedContainer loadEncrypted(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = fis.readAllBytes();
            return EncryptedContainer.fromString(
                new String(data, StandardCharsets.UTF_8)
            );
        }
    }

    /**
     * Container for encrypted data.
     */
    public record EncryptedContainer(
        String encryptedSessionKey,
        String iv,
        String encryptedPayload
    ) {
        public String toString() {
            return encryptedSessionKey + ":" + iv + ":" +encryptedPayload;
        }

        public static EncryptedContainer fromString(String data) {
            String[] parts = data.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException(
                    "Invalid encrypted container format"
                );
            }
            return new EncryptedContainer(parts[0], parts[1], parts[2]);
        }
    }
}
```

### Using CryptoHelper

Initialize the crypto helper after obtaining your certificate:

```java
public class MyPlugin extends JavaPlugin {

    private ValleyCert valleyCert;
    private CryptoHelper cryptoHelper;

    @Override
    public void onEnable() {
        valleyCert = new ValleyCert(getDataFolder().toPath());

        if (!valleyCert.initialize("my-plugin", new String[]{"VLINK"}, 90)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize crypto helper with the certificate
        cryptoHelper = new CryptoHelper(valleyCert.getCertificate());
    }

    public CryptoHelper getCryptoHelper() {
        return cryptoHelper;
    }
}
```

Encrypt data:

```java
try {
    CryptoHelper.EncryptedContainer encrypted = cryptoHelper.encrypt("sensitive data");
    getLogger().info("Encrypted session key: " +
        encrypted.encryptedSessionKey().substring(0, 20) + "...");
    getLogger().info("IV: " + encrypted.iv());
    getLogger().info("Encrypted payload: " +
        encrypted.encryptedPayload().substring(0, 20) + "...");
} catch (Exception e) {
    getLogger().severe("Encryption failed: " + e.getMessage());
}
```

### AES-GCM Details

| Parameter | Value | Why |
|---|---|---|
| Algorithm | `AES/GCM/NoPadding` | Galois/Counter Mode provides both encryption and authentication |
| Key size | 256 bits | Strong symmetric encryption |
| IV size | 12 bytes (96 bits) | GCM standard recommends 12-byte IVs |
| Tag size | 128 bits | Full authentication tag for maximum integrity |

### ECIES Details

| Parameter | Value | Why |
|---|---|---|
| Algorithm | `ECIES` | Elliptic Curve Integrated Encryption Scheme |
| Curve | P-256 (secp256r1) | NIST standard curve, used by the CA |
| Key format | X.509 encoded | Standard public key encoding |

---

## Storing Encrypted Data

Once you have an `EncryptedContainer`, you need to persist it. The container serializes to a colon-delimited string that can be written to files or stored in databases.

### Serialization Format

The `EncryptedContainer.toString()` method produces:

```
<encryptedSessionKey>:<iv>:<encryptedPayload>
```

All three parts are Base64-encoded, so they contain only alphanumeric characters, `+`, `/`, and `=`. The colon `:` is the delimiter.

### File Storage

```java
// Save to file
File encryptedFile = new File(getDataFolder(), "encrypted-data.dat");
cryptoHelper.saveEncrypted(encryptedFile, encryptedContainer);

// Load from file
EncryptedContainer loaded = cryptoHelper.loadEncrypted(encryptedFile);
```

### Database Storage

Store each field separately in your database:

```java
CryptoHelper.EncryptedContainer container = cryptoHelper.encrypt("player data");

// Store in database (example with PreparedStatement)
PreparedStatement stmt = connection.prepareStatement(
    "INSERT INTO encrypted_data (player_uuid, session_key, iv, payload) VALUES (?, ?, ?, ?)"
);
stmt.setString(1, playerUuid.toString());
stmt.setString(2, container.encryptedSessionKey());
stmt.setString(3, container.iv());
stmt.setString(4, container.encryptedPayload());
stmt.executeUpdate();
```

### JSON Storage

```java
CryptoHelper.EncryptedContainer container = cryptoHelper.encrypt("player data");

JsonObject json = new JsonObject();
json.addProperty("sessionKey", container.encryptedSessionKey());
json.addProperty("iv", container.iv());
json.addProperty("payload", container.encryptedPayload());
json.addProperty("certificateId", valleyCert.getCertificate().getCertificateId());

// Write to file
Files.writeString(getDataFolder().toPath().resolve("data.json"), json.toString());
```

### Important: Decryption is Server-Side Only

You cannot decrypt data on the client side. The CA's private key is never exposed to plugins. Decryption must happen on a server that has access to the CA's private key, typically through the ValleyCert API endpoint.

```
Client (your plugin)              Server (CA)
     |                               |
     |  EncryptedContainer           |
     |  {encSessionKey, iv, encPay}  |
     |------------------------------>|
     |                               |
     |                    Decrypt with CA private key
     |                               |
     |  Plaintext data               |
     |<------------------------------|
```

---

## Error Handling

ValleyCert uses a fail-closed approach: if anything goes wrong, operations are denied rather than permitted.

### Common Error Scenarios

| Scenario | What Happens | How to Handle |
|---|---|---|
| ValleyAuth not installed | `initialize()` returns `false`, all operations disabled | Disable your plugin with `disablePlugin(this)` |
| CA unreachable | `initialize()` returns `false` | Log the error, disable your plugin or degrade gracefully |
| Certificate expired | `validateCapability()` returns `false` | Log a warning, do not perform the protected operation |
| Certificate revoked | `validateCapability()` returns `false` | Log a security warning, do not perform the operation |
| Missing capability | `validateCapability()` returns `false` | Log a warning, inform the user the operation is not permitted |
| Invalid signature | `validateCapability()` returns `false` | Log a security warning, the certificate may be tampered |
| `getCertificate()` when disabled | Returns `null` | Always null-check before using the certificate |

### Handling ValleyAuth Not Installed

```java
@Override
public void onEnable() {
    valleyCert = new ValleyCert(getDataFolder().toPath());

    if (!valleyCert.initialize("my-plugin", new String[]{"VLINK"}, 90)) {
        // This covers both "ValleyAuth not found" and "CA unreachable"
        getLogger().severe("Failed to initialize ValleyCert. Disabling plugin.");
        getServer().getPluginManager().disablePlugin(this);
        return;
    }

    // Plugin is ready
}
```

### Handling Certificate Errors Gracefully

```java
public void performOperation() {
    // Check if ValleyCert is initialized
    if (!valleyCert.isInitialized()) {
        getLogger().warning("ValleyCert not initialized. Cannot perform operation.");
        return;
    }

    // Check capability
    if (!valleyCert.validateCapability("VLINK")) {
        getLogger().warning("Missing VLINK capability.");
        return;
    }

    // Get certificate
    CertificateData cert = valleyCert.getCertificate();
    if (cert == null) {
        getLogger().severe("Certificate is null despite initialization check passing.");
        return;
    }

    // Check expiry explicitly if needed
    if (cert.isExpired()) {
        getLogger().warning("Certificate has expired. Waiting for renewal.");
        return;
    }

    // Check if renewal is needed
    if (cert.needsRenewal()) {
        getLogger().info("Certificate needs renewal soon. Automatic renewal is scheduled.");
    }

    // Safe to proceed
    // ...
}
```

### Logging Best Practices

Use the `[ValleyCert]` prefix for all log messages so server administrators can filter logs:

```java
getLogger().info("[MyPlugin] Certificate loaded: " + cert.getCertificateId());
getLogger().warning("[MyPlugin] Missing capability: VLINK");
getLogger().severe("[MyPlugin] Certificate validation failed");
```

---

## Best Practices

### 1. Fail Closed

Always disable your plugin if initialization fails. Never allow your plugin to operate without a valid certificate.

```java
// CORRECT: Fail closed
if (!valleyCert.initialize("my-plugin", capabilities, 90)) {
    getServer().getPluginManager().disablePlugin(this);
    return;
}

// WRONG: Continue without certificate
valleyCert.initialize("my-plugin", capabilities, 90);
// Plugin continues operating even if cert failed
```

### 2. Request Minimal Capabilities

Only request capabilities your plugin actually uses. If your plugin only does VLink operations, do not request `MIGRATION_PROVIDER` or other unnecessary capabilities.

```java
// CORRECT: Request only what you need
String[] capabilities = {"VLINK"};

// WRONG: Request everything "just in case"
String[] capabilities = {
    "VLINK", "IDENTITY_LINK", "RANK_SHARE",
    "MIGRATION_PROVIDER", "MIGRATION_ACCESS",
    "CERTIFICATE_MANAGEMENT"
};
```

### 3. Check Capabilities Before Every Operation

Do not assume capabilities remain valid. Check them every time you perform a protected operation.

```java
// CORRECT: Check every time
public void doVLink() {
    if (!valleyCert.validateCapability("VLINK")) {
        return;
    }
    // Perform operation
}

// WRONG: Check once at startup and assume it stays valid
@Override
public void onEnable() {
    hasVlink = valleyCert.validateCapability("VLINK");
}

public void doVLink() {
    if (hasVlink) {  // This could be stale if cert was renewed with different caps
        // Perform operation
    }
}
```

### 4. Cache Certificate References Appropriately

Get a fresh reference to the certificate when you need it, rather than caching it for long periods:

```java
// CORRECT: Get fresh reference when needed
public void performAction() {
    CertificateData cert = valleyCert.getCertificate();
    if (cert == null) {
        return;
    }
    // Use cert
}

// acceptable: Cache for short-lived operations
public void handleCommand() {
    CertificateData cert = valleyCert.getCertificate();
    if (cert == null || !cert.isCurrentlyValid()) {
        return;
    }
    // Use cert for this command execution only
}
```

### 5. Use softdepend, Not depend

Always use `softdepend` for ValleyAuth in your `plugin.yml`. This lets your plugin load first and handle the missing dependency gracefully instead of preventing the entire server from starting.

```yaml
# CORRECT
softdepend:
  - ValleyAuth

# WRONG: This prevents server startup if ValleyAuth is missing
depend:
  - ValleyAuth
```

### 6. Validate Certificate State

Always check the certificate status, not just the capability:

```java
CertificateData cert = valleyCert.getCertificate();
if (cert != null && cert.getStatus() == CertificateData.CertificateStatus.ACTIVE) {
    // Certificate is active, proceed
}
```

### 7. Handle Renewal Gracefully

Certificate renewal is automatic, but during the brief renewal window your plugin should handle the case where the certificate might temporarily be in transition:

```java
// The renewal process is atomic: old cert stays valid until new one is verified
// Your plugin does not need to handle this explicitly, but be aware that
// certificate details (like expiration date) may change after renewal
```

### 8. Secure Certificate Storage

ValleyCert stores certificates at `<plugin-data>/certs/<pluginId>/valley.cert`. Make sure your server's file permissions protect this directory:

```
plugins/
  MyPlugin/
    certs/
      my-plugin/
        valley.cert       # Certificate file (JSON)
        valley.cert.backup  # Backup of previous certificate
```

---

## API Reference

### ValleyCert Class

| Method | Signature | Returns | Description |
|---|---|---|---|
| Constructor | `ValleyCert(Path pluginDataFolder)` | - | Create a new ValleyCert instance |
| initialize | `initialize(String pluginId, String[] capabilities, int requestedLifetimeDays)` | `boolean` | Request and validate a certificate |
| validateCapability | `validateCapability(String capability)` | `boolean` | Check if certificate has a capability |
| getCertificate | `getCertificate()` | `CertificateData` | Get the current certificate (null if disabled) |
| isInitialized | `isInitialized()` | `boolean` | True if enabled, initialized, and certificate is valid |
| getCertificatePath | `getCertificatePath(String pluginId)` | `Path` | Get the on-disk path for a plugin's certificate |

### CertificateData Class

| Method | Returns | Description |
|---|---|---|
| `getPluginId()` | `String` | Plugin that owns this certificate |
| `getCertificateId()` | `String` | UUID identifier |
| `getCapabilities()` | `List<String>` | Granted capabilities |
| `getIssuanceDate()` | `Date` | Issue date (defensive copy) |
| `getExpirationDate()` | `Date` | Expiration date (defensive copy) |
| `getIssuer()` | `String` | Certificate issuer |
| `getSignature()` | `String` | ECDSA signature |
| `getPublicKey()` | `String` | EC public key |
| `getStatus()` | `CertificateStatus` | ACTIVE, EXPIRED, REVOKED, or SUSPENDED |
| `isCurrentlyValid()` | `boolean` | Active and within date range |
| `isExpired()` | `boolean` | Past expiration date |
| `needsRenewal()` | `boolean` | Within 7 days of expiry |

### CertificateStatus Enum

| Value | Description |
|---|---|
| `ACTIVE` | Certificate is valid and operational |
| `EXPIRED` | Certificate has passed its expiration date |
| `REVOKED` | Certificate was revoked by the CA |
| `SUSPENDED` | Certificate is temporarily suspended |

### CertificateRequestResult Class

| Method | Returns | Description |
|---|---|---|
| `isSuccess()` | `boolean` | Whether the request succeeded |
| `getCertificate()` | `CertificateData` | The certificate (null on failure) |
| `getErrorMessage()` | `String` | Error description (null on success) |

---

## FAQ

### Can I use ValleyCert without ValleyAuth?

No. ValleyCert requires ValleyAuth to be installed on the server. ValleyAuth acts as the intermediary between your plugin and the Certificate Authority. Without ValleyAuth, `initialize()` returns `false` and all certificate operations are disabled.

### What happens if the CA is offline?

If the Certificate Authority is unreachable when you call `initialize()`, the method returns `false`. Your plugin should handle this by disabling itself or operating in a degraded mode that does not require certificate-protected operations.

### Can I decrypt data encrypted with my certificate?

No. Decryption requires the CA's private key, which is never exposed to client plugins. Your plugin can encrypt data, but decryption must happen on a server that has access to the CA's private key, typically through the ValleyCert API endpoint.

### How long does a certificate last?

The default lifetime is 90 days. You can request up to 120 days. The CA may issue a shorter certificate depending on its policy. Certificates are automatically renewed within 7 days of expiry.

### What if I need capabilities added after issuance?

Capabilities cannot be added to an existing certificate. You must request a new certificate with the additional capabilities. To do this, delete the cached certificate file at `<plugin-data>/certs/<pluginId>/valley.cert` and reinitialize.

### Do I need to handle certificate renewal manually?

No. ValleyCert includes a background scheduler that checks certificates hourly and triggers automatic renewal within 7 days of expiry. Your plugin does not need to manage renewal.

### What is a mock certificate?

When ValleyCert cannot reach the CA (for example, during local development), it generates a mock certificate with a `"mock-signature"` prefix. Mock certificates bypass ECDSA signature verification and are useful for testing. They are not accepted by production ValleyAuth APIs.

### How do I know if my certificate was revoked?

ValleyCert checks the `encryptedRevocationTimestamp` field on every validation call. If this field is non-null, the certificate is treated as revoked and all operations are denied. You will see log messages with `[ValleyCert Validator]` indicating the revocation.

### Can I use ValleyCert in a BungeeCord or Velocity proxy?

ValleyCert is designed for Paper server plugins. It depends on the Paper API (`org.bukkit.Bukkit`) for plugin detection. It is not compatible with BungeeCord or Velocity without modification.

### Where is the certificate stored on disk?

Certificates are stored at:
```
<plugin-data-folder>/certs/<plugin-id>/valley.cert
```

For example, if your plugin ID is `"my-plugin"` and your plugin data folder is `plugins/MyPlugin`, the certificate is at:
```
plugins/MyPlugin/certs/my-plugin/valley.cert
```

### How do I test my plugin locally without a CA?

ValleyCert automatically falls back to mock certificates when the CA is unreachable. Simply install ValleyAuth on your test server and call `initialize()`. A mock certificate will be generated with the capabilities you requested. This is sufficient for local development and testing.

### What algorithms does ValleyCert use?

| Purpose | Algorithm | Details |
|---|---|---|
| Certificate signing | ECDSA | SHA256withECDSA on P-256 curve |
| Payload encryption | AES-GCM | 256-bit key, 12-byte IV, 128-bit auth tag |
| Session key encryption | ECIES | Elliptic Curve Integrated Encryption Scheme |
| Public key encoding | X.509 | Standard EC public key format |
| Password hashing (ValleyAuth) | PBKDF2 | Used for user authentication, not certificates |

### How do I handle certificate errors in commands?

Always check capabilities and certificate state before performing operations:

```java
@Override
public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
        sender.sendMessage("Players only.");
        return true;
    }

    if (!valleyCert.isInitialized()) {
        player.sendMessage("Certificate not available.");
        return true;
    }

    if (!valleyCert.validateCapability("VLINK")) {
        player.sendMessage("Missing VLINK capability.");
        return true;
    }

    // Safe to perform operation
    return true;
}
```

### Can I run multiple plugins that use ValleyCert?

Yes. Each plugin creates its own `ValleyCert` instance and requests its own certificate. Certificate storage is isolated by plugin ID, so there are no conflicts.

### What happens if ValleyAuth is reloaded?

If ValleyAuth is reloaded while your plugin is running, your plugin's certificate remains valid (it is cached on disk and in memory). However, if the CA configuration changes, new certificate requests may fail. Restart the server to ensure all plugins reinitialize cleanly.

---

## Complete Example: Full Plugin

Here is a complete example combining all the patterns from this guide:

```java
package com.example.myplugin;

import com.valleyrealm.valleycert.ValleyCert;
import com.valleyrealm.valleycert.CertificateData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    private ValleyCert valleyCert;
    private CryptoHelper cryptoHelper;

    @Override
    public void onEnable() {
        getLogger().info("MyPlugin enabling...");

        // Initialize ValleyCert
        valleyCert = new ValleyCert(getDataFolder().toPath());

        String[] capabilities = {"VLINK", "IDENTITY_LINK"};
        boolean success = valleyCert.initialize("my-plugin", capabilities, 90);

        if (!success) {
            getLogger().severe("Failed to obtain certificate. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize crypto helper
        cryptoHelper = new CryptoHelper(valleyCert.getCertificate());

        // Register commands
        getCommand("myplugin").setExecutor(new MyPluginCommandExecutor(this));

        getLogger().info("MyPlugin enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("MyPlugin disabled.");
    }

    public ValleyCert getValleyCert() {
        return valleyCert;
    }

    public CryptoHelper getCryptoHelper() {
        return cryptoHelper;
    }
}
```

```java
package com.example.myplugin;

import com.valleyrealm.valleycert.CertificateData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MyPluginCommandExecutor implements CommandExecutor {

    private final MyPlugin plugin;

    public MyPluginCommandExecutor(MyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (!player.hasPermission("myplugin.admin")) {
            player.sendMessage("No permission.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "cert" -> showCertificate(player);
            case "encrypt" -> handleEncrypt(player, args);
            case "validate" -> handleValidate(player);
            default -> sendUsage(player);
        }

        return true;
    }

    private void showCertificate(Player player) {
        CertificateData cert = plugin.getValleyCert().getCertificate();
        if (cert == null) {
            player.sendMessage("No certificate loaded.");
            return;
        }

        player.sendMessage("=== Certificate Info ===");
        player.sendMessage("Plugin ID: " + cert.getPluginId());
        player.sendMessage("Certificate ID: " + cert.getCertificateId());
        player.sendMessage("Issuer: " + cert.getIssuer());
        player.sendMessage("Status: " + cert.getStatus());
        player.sendMessage("Issued: " + cert.getIssuanceDate());
        player.sendMessage("Expires: " + cert.getExpirationDate());
        player.sendMessage("Capabilities: " + cert.getCapabilities());
    }

    private void handleEncrypt(Player player, String[] args) {
        if (!plugin.getValleyCert().validateCapability("VLINK")) {
            player.sendMessage("Missing VLINK capability.");
            return;
        }

        if (args.length < 2) {
            player.sendMessage("Usage: /myplugin encrypt <message>");
            return;
        }

        String message = args[1];
        try {
            CryptoHelper.EncryptedContainer encrypted =
                plugin.getCryptoHelper().encrypt(message);
            player.sendMessage("=== Encrypted Data ===");
            player.sendMessage("Session Key: " +
                encrypted.encryptedSessionKey().substring(0, 20) + "...");
            player.sendMessage("IV: " + encrypted.iv());
            player.sendMessage("Payload: " +
                encrypted.encryptedPayload().substring(0, 20) + "...");
        } catch (Exception e) {
            player.sendMessage("Encryption failed: " + e.getMessage());
            plugin.getLogger().warning("Encryption error: " + e.getMessage());
        }
    }

    private void handleValidate(Player player) {
        boolean valid = plugin.getValleyCert().isInitialized();
        if (valid) {
            player.sendMessage("Certificate is valid and initialized.");
        } else {
            player.sendMessage("Certificate is not valid or not initialized.");
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage("[MyPlugin] Commands:");
        player.sendMessage("/myplugin cert - Show certificate info");
        player.sendMessage("/myplugin encrypt <msg> - Encrypt a message");
        player.sendMessage("/myplugin validate - Validate certificate");
    }
}
```

---

## Troubleshooting

### ValleyCert is disabled

**Symptom:** Logs show `[ValleyCert] WARNING: ... called but ValleyCert is disabled`

**Cause:** ValleyAuth plugin is not installed on the server.

**Fix:** Install ValleyAuth in the `plugins/` directory and restart the server.

### Certificate request fails

**Symptom:** `initialize()` returns `false`

**Cause:** The Certificate Authority is unreachable or misconfigured.

**Fix:**
1. Check that ValleyAuth is installed and running
2. Verify ValleyAuth's config has a valid `certificate.api-url`
3. Check server logs for `[ValleyCert]` error messages
4. Ensure the CA server is online

### Missing capabilities

**Symptom:** `validateCapability("VLINK")` returns `false`

**Cause:** The certificate was issued without the requested capability, or the certificate needs to be reissued.

**Fix:**
1. Delete the cached certificate file at `<plugin-data>/certs/<pluginId>/valley.cert`
2. Restart your plugin to trigger a new certificate request
3. Verify the capabilities array in your `initialize()` call

### Signature verification fails

**Symptom:** Logs show `[ValleyCert Validator] Invalid certificate signature`

**Cause:** The certificate may have been tampered with, or the CA key has changed.

**Fix:**
1. Delete the cached certificate
2. Restart the server to request a fresh certificate
3. If the issue persists, the CA may need to be reconfigured

### Certificate expires before renewal

**Symptom:** Plugin stops working after certificate expires

**Cause:** The renewal scheduler could not reach the CA during the 7-day renewal window.

**Fix:**
1. Ensure the CA is reachable
2. Check logs for `[ValleyCert Renewer]` messages
3. The plugin will automatically obtain a new certificate on next restart

---

*This guide covers ValleyCert 0.2.0-alpha and ValleyAuth 0.2.0-alpha. For the latest updates, refer to the ValleyRealm repository.*
