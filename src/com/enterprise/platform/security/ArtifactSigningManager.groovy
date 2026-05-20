package com.enterprise.platform.security

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils

class ArtifactSigningManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> SIGNATURE_EXTENSIONS = [".asc", ".sig", ".sign"]
    private static final String DEFAULT_KEYRING_PATH = "\${user.home}/.gnupg"
    private static final int MAX_SIGNATURE_RETRIES = 2

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final ShellUtils shellUtils
    private final String correlationId

    private String gpgBinaryPath = "gpg"
    private String keyringPath = DEFAULT_KEYRING_PATH
    private boolean useAgent = true

    ArtifactSigningManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    ArtifactSigningManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = correlationId
    }

    Map signArtifact(String artifactPath, Map signingKey) {
        LoggingUtils.info("ArtifactSigningManager",
            "Signing artifact '${artifactPath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(artifactPath)) {
            throw new IllegalArgumentException("Artifact path must not be null or empty")
        }
        if (signingKey == null) {
            throw new IllegalArgumentException("Signing key configuration must not be null")
        }

        long startTime = System.currentTimeMillis()

        try {
            configureFromKey(signingKey)

            if (!steps.fileExists(artifactPath)) {
                throw new RuntimeException("Artifact file not found: '${artifactPath}'")
            }

            String keyId = resolveKeyId(signingKey)
            String passphrase = resolvePassphrase(signingKey)
            String signaturePath = "${artifactPath}.asc"

            verifySigningKey(keyId)

            String cmd = buildSignCommand(artifactPath, signaturePath, keyId, passphrase)

            Map execResult = shellUtils.execute(cmd, [
                timeoutMs: 120000,
                captureOutput: true,
                validExitCodes: [0]
            ])

            if (!steps.fileExists(signaturePath)) {
                throw new RuntimeException("Signature file was not created at '${signaturePath}'")
            }

            long duration = System.currentTimeMillis() - startTime
            long signatureSize = getFileSize(signaturePath)

            LoggingUtils.info("ArtifactSigningManager",
                "Artifact signed successfully in ${duration}ms: key=${keyId}, signature=${signaturePath} (${signatureSize} bytes) [correlationId=${correlationId}]")

            audit.emitAuditEvent("ARTIFACT_SIGNED",
                "Artifact '${artifactPath}' signed with key ${keyId}", correlationId)
            telemetry.emitEvent("security.signing", "artifact_signed", [
                correlationId: correlationId,
                artifactPath: artifactPath,
                keyId: keyId,
                signaturePath: signaturePath,
                signatureSize: signatureSize,
                durationMs: duration
            ])

            return [
                status: "SIGNED",
                correlationId: correlationId,
                artifactPath: artifactPath,
                signaturePath: signaturePath,
                keyId: keyId,
                durationMs: duration,
                signatureSize: signatureSize
            ]

        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - startTime
            String errMsg = "Artifact signing failed for '${artifactPath}': ${e.message}"
            LoggingUtils.error("ArtifactSigningManager", errMsg, e)
            audit.emitAuditEvent("ARTIFACT_SIGN_FAILED", errMsg, correlationId)
            telemetry.emitEvent("security.signing", "sign_failed", [
                correlationId: correlationId,
                artifactPath: artifactPath,
                durationMs: duration,
                error: e.message
            ])
            throw new RuntimeException(errMsg, e)

        } catch (Exception e) {
            String errMsg = "Unexpected signing error for '${artifactPath}': ${e.message}"
            LoggingUtils.error("ArtifactSigningManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map signArtifacts(List<String> artifactPaths, Map signingKey) {
        LoggingUtils.info("ArtifactSigningManager",
            "Signing ${artifactPaths.size()} artifact(s) [correlationId=${correlationId}]")

        if (artifactPaths == null || artifactPaths.isEmpty()) {
            throw new IllegalArgumentException("Artifact paths list must not be null or empty")
        }

        long startTime = System.currentTimeMillis()
        List<Map> results = []
        int successCount = 0
        int failureCount = 0
        List<String> failedArtifacts = []

        for (String artifactPath : artifactPaths) {
            try {
                Map result = signArtifact(artifactPath, signingKey)
                results.add(result)
                successCount++
            } catch (Exception e) {
                failureCount++
                failedArtifacts.add(artifactPath)
                LoggingUtils.warn("ArtifactSigningManager",
                    "Failed to sign artifact '${artifactPath}': ${e.message} [correlationId=${correlationId}]")
                results.add([
                    status: "FAILED",
                    artifactPath: artifactPath,
                    error: e.message
                ])
            }
        }

        long duration = System.currentTimeMillis() - startTime
        String summary = "${successCount} signed, ${failureCount} failed"

        LoggingUtils.info("ArtifactSigningManager",
            "Batch signing completed in ${duration}ms: ${summary} [correlationId=${correlationId}]")
        audit.emitAuditEvent("ARTIFACT_BATCH_SIGNED",
            "Batch signing completed: ${summary}", correlationId)
        telemetry.emitEvent("security.signing", "batch_signed", [
            correlationId: correlationId,
            totalArtifacts: artifactPaths.size(),
            successCount: successCount,
            failureCount: failureCount,
            durationMs: duration
        ])

        return [
            status: failureCount == 0 ? "ALL_SIGNED" : "PARTIAL",
            correlationId: correlationId,
            durationMs: duration,
            totalArtifacts: artifactPaths.size(),
            successCount: successCount,
            failureCount: failureCount,
            failedArtifacts: failedArtifacts,
            results: results
        ]
    }

    Map verifySignature(String artifactPath, String signaturePath) {
        LoggingUtils.info("ArtifactSigningManager",
            "Verifying signature for '${artifactPath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(artifactPath)) {
            throw new IllegalArgumentException("Artifact path must not be null or empty")
        }

        long startTime = System.currentTimeMillis()

        try {
            if (signaturePath == null || !steps.fileExists(signaturePath)) {
                String autoSig = "${artifactPath}.asc"
                if (steps.fileExists(autoSig)) {
                    signaturePath = autoSig
                } else {
                    for (String ext : SIGNATURE_EXTENSIONS) {
                        String candidate = artifactPath + ext
                        if (steps.fileExists(candidate)) {
                            signaturePath = candidate
                            break
                        }
                    }
                }
                if (signaturePath == null) {
                    throw new RuntimeException("No signature file found for '${artifactPath}'")
                }
            }

            String cmd = "${gpgBinaryPath} --verify '${signaturePath}' '${artifactPath}' 2>&1"

            Map execResult = shellUtils.execute(cmd, [
                timeoutMs: 60000,
                captureOutput: true,
                validExitCodes: [0, 1]
            ])

            String output = execResult.stdout?.toString() ?: ""
            int exitCode = execResult.exitCode instanceof Number ?
                ((Number) execResult.exitCode).intValue() : -1

            boolean valid = exitCode == 0
            String keyId = extractKeyIdFromVerify(output)
            String fingerprint = extractFingerprint(output)

            long duration = System.currentTimeMillis() - startTime

            LoggingUtils.info("ArtifactSigningManager",
                "Signature verification ${valid ? 'PASSED' : 'FAILED'} in ${duration}ms [correlationId=${correlationId}]")

            audit.emitAuditEvent("SIGNATURE_VERIFICATION",
                "Signature verification ${valid ? 'passed' : 'failed'} for '${artifactPath}'", correlationId)
            telemetry.emitEvent("security.signing", "verification_completed", [
                correlationId: correlationId,
                artifactPath: artifactPath,
                valid: valid,
                keyId: keyId,
                durationMs: duration
            ])

            return [
                status: valid ? "VALID" : "INVALID",
                correlationId: correlationId,
                artifactPath: artifactPath,
                signaturePath: signaturePath,
                valid: valid,
                keyId: keyId,
                fingerprint: fingerprint,
                durationMs: duration,
                verificationOutput: sanitizeOutput(output)
            ]

        } catch (Exception e) {
            Long duration = System.currentTimeMillis() - startTime
            String errMsg = "Signature verification failed for '${artifactPath}': ${e.message}"
            LoggingUtils.error("ArtifactSigningManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map importSigningKey(String keyFile, String passphrase) {
        LoggingUtils.info("ArtifactSigningManager",
            "Importing signing key from '${keyFile}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(keyFile)) {
            throw new IllegalArgumentException("Key file path must not be null or empty")
        }
        if (!steps.fileExists(keyFile)) {
            throw new RuntimeException("Key file not found: '${keyFile}'")
        }

        try {
            List<String> cmdArgs = [gpgBinaryPath, "--batch", "--import", keyFile]
            if (ValidationUtils.isNonEmpty(passphrase)) {
                cmdArgs.add(0, "--passphrase=${maskPassphrase(passphrase)}")
            }

            String cmd = cmdArgs.join(" ")

            Map execResult = shellUtils.execute(cmd, [
                timeoutMs: 60000,
                captureOutput: true,
                validExitCodes: [0]
            ])

            String importedKeyId = extractImportedKeyId(execResult.stdout?.toString() ?: "")

            LoggingUtils.info("ArtifactSigningManager",
                "Signing key imported: ${importedKeyId} [correlationId=${correlationId}]")
            audit.emitAuditEvent("SIGNING_KEY_IMPORTED",
                "GPG signing key imported: ${importedKeyId}", correlationId)

            return [
                status: "IMPORTED",
                keyId: importedKeyId,
                keyFile: keyFile
            ]

        } catch (Exception e) {
            String errMsg = "Failed to import signing key from '${keyFile}': ${e.message}"
            LoggingUtils.error("ArtifactSigningManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    void setGpgBinaryPath(String path) {
        if (ValidationUtils.isNonEmpty(path)) {
            this.gpgBinaryPath = path
        }
    }

    void setKeyringPath(String path) {
        if (ValidationUtils.isNonEmpty(path)) {
            this.keyringPath = path
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    private void configureFromKey(Map signingKey) {
        if (signingKey.gpgBinary instanceof String) {
            this.gpgBinaryPath = signingKey.gpgBinary.toString()
        }
        if (signingKey.keyringPath instanceof String) {
            this.keyringPath = signingKey.keyringPath.toString()
        }
        if (signingKey.useAgent instanceof Boolean) {
            this.useAgent = (Boolean) signingKey.useAgent
        }
    }

    private void verifySigningKey(String keyId) {
        try {
            String cmd = "${gpgBinaryPath} --list-keys --keyid-format LONG '${keyId}' 2>&1"
            shellUtils.execute(cmd, [
                timeoutMs: 15000,
                captureOutput: true,
                validExitCodes: [0]
            ])
        } catch (Exception e) {
            String errMsg = "Signing key '${keyId}' not found in GPG keyring. " +
                "Import the key before signing."
            LoggingUtils.error("ArtifactSigningManager", errMsg, null)
            throw new RuntimeException(errMsg)
        }
    }

    @NonCPS
    private String buildSignCommand(String artifactPath, String signaturePath,
                                    String keyId, String passphrase) {
        List<String> args = []

        args.add(gpgBinaryPath)
        args.add("--batch")
        args.add("--no-tty")
        args.add("--yes")
        args.add("--armor")
        args.add("--detach-sign")

        if (!useAgent) {
            args.add("--no-use-agent")
        }

        if (ValidationUtils.isNonEmpty(keyId)) {
            args.add("--local-user")
            args.add(keyId)
        }

        if (ValidationUtils.isNonEmpty(passphrase)) {
            args.add("--passphrase=${maskPassphrase(passphrase)}")
        } else {
            args.add("--passphrase=''")
        }

        args.add("--output")
        args.add(signaturePath)
        args.add(artifactPath)

        return args.join(" ")
    }

    @NonCPS
    private String resolveKeyId(Map signingKey) {
        String keyId = signingKey.keyId instanceof String ?
            signingKey.keyId.toString() : ""

        if (!ValidationUtils.isNonEmpty(keyId)) {
            keyId = signingKey.keyName instanceof String ?
                signingKey.keyName.toString() : ""
        }

        if (!ValidationUtils.isNonEmpty(keyId)) {
            keyId = signingKey.keyFingerprint instanceof String ?
                signingKey.keyFingerprint.toString() : ""
        }

        if (!ValidationUtils.isNonEmpty(keyId)) {
            throw new IllegalArgumentException(
                "Signing key ID, name, or fingerprint must be specified")
        }

        return keyId
    }

    @NonCPS
    private String resolvePassphrase(Map signingKey) {
        if (signingKey.passphrase instanceof String) {
            return signingKey.passphrase.toString()
        }
        if (signingKey.passphraseEnv instanceof String) {
            String env = signingKey.passphraseEnv.toString()
            try {
                return steps.env.getProperty(env) ?: ""
            } catch (Exception e) {
                return ""
            }
        }
        return ""
    }

    @NonCPS
    private String maskPassphrase(String passphrase) {
        if (!ValidationUtils.isNonEmpty(passphrase)) return ""
        return passphrase
    }

    private long getFileSize(String path) {
        try {
            String result = steps.sh(
                script: "stat --format=%s '${path}' 2>/dev/null || echo 0",
                returnStdout: true
            ).toString().trim()
            return Long.parseLong(result)
        } catch (Exception e) {
            return 0
        }
    }

    @NonCPS
    private String extractKeyIdFromVerify(String verifyOutput) {
        if (!ValidationUtils.isNonEmpty(verifyOutput)) return ""
        def matcher = verifyOutput =~ /key\s+([A-F0-9]{16})/
        if (matcher.find()) return matcher.group(1)
        matcher = verifyOutput =~ /([A-F0-9]{16})/
        if (matcher.find()) return matcher.group(1)
        return ""
    }

    @NonCPS
    private String extractFingerprint(String verifyOutput) {
        if (!ValidationUtils.isNonEmpty(verifyOutput)) return ""
        def matcher = verifyOutput =~ /fingerprint:\s+([A-F0-9 ]+)/
        if (matcher.find()) return matcher.group(1).trim()
        return ""
    }

    @NonCPS
    private String extractImportedKeyId(String importOutput) {
        if (!ValidationUtils.isNonEmpty(importOutput)) return ""
        def matcher = importOutput =~ /key\s+([A-F0-9]+):/
        if (matcher.find()) return matcher.group(1)
        matcher = importOutput =~ /([A-F0-9]{16,})/
        if (matcher.find()) return matcher.group(1)
        return "unknown"
    }

    @NonCPS
    private String sanitizeOutput(String output) {
        if (output == null) return ""
        return output.replaceAll("(?i)(passphrase|password|secret)[=:][A-Za-z0-9_!@#\$%^&*()]+", "\$1:***")
    }
}
