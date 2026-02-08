#!/usr/bin/env kotlin

/**
 * Command-line test program for spatial audio panning calculations.
 *
 * Usage:
 *   kotlin test_panning.kts
 *
 * Or make executable and run directly:
 *   chmod +x test_panning.kts
 *   ./test_panning.kts
 */

import kotlin.math.*
import kotlin.system.exitProcess

// ============================================================================
// Core Panning Logic (copied from SpatialMixer.kt)
// ============================================================================

data class StereoPan(val left: Float, val right: Float)

fun calculateRelativeAngle(phoneAzimuth: Float, deviceWorldAzimuth: Float): Float {
    val phoneRad = Math.toRadians(phoneAzimuth.toDouble())
    val deviceRad = Math.toRadians(deviceWorldAzimuth.toDouble())
    val deltaRad = deviceRad - phoneRad
    val normalizedRad = atan2(sin(deltaRad), cos(deltaRad))
    return Math.toDegrees(normalizedRad).toFloat()
}

fun calculateStereoPan(relativeAngle: Float, behindAttenuation: Float = 1.0f): StereoPan {
    var dPrime = relativeAngle
    while (dPrime > 90f) {
        dPrime = 180f - dPrime
    }
    while (dPrime < -90f) {
        dPrime = -180f - dPrime
    }

    val absDPrime = abs(dPrime)
    val panPosition = absDPrime / 90f

    var rightGain = sqrt((panPosition + 1f) / 2f)
    var leftGain = sqrt((1f - panPosition) / 2f)

    if (dPrime < 0f) {
        val temp = leftGain
        leftGain = rightGain
        rightGain = temp
    }

    val attenuation = if (abs(relativeAngle) > 90f) behindAttenuation else 1.0f

    return StereoPan(
        left = leftGain * attenuation,
        right = rightGain * attenuation
    )
}

// ============================================================================
// Test Utilities
// ============================================================================

var testsPassed = 0
var testsFailed = 0

fun assert(condition: Boolean, message: String) {
    if (condition) {
        testsPassed++
        println("✓ PASS: $message")
    } else {
        testsFailed++
        println("✗ FAIL: $message")
    }
}

fun assertFloatEquals(actual: Float, expected: Float, tolerance: Float, message: String) {
    val diff = abs(actual - expected)
    if (diff <= tolerance) {
        testsPassed++
        println("✓ PASS: $message (actual=${"%.3f".format(actual)}, expected=${"%.3f".format(expected)})")
    } else {
        testsFailed++
        println("✗ FAIL: $message (actual=${"%.3f".format(actual)}, expected=${"%.3f".format(expected)}, diff=${"%.3f".format(diff)})")
    }
}

fun printPanTable(title: String, angles: List<Int>) {
    println("\n$title")
    println("=" .repeat(60))
    println("Angle    d'    Pan    Left   Right   L²+R²   Notes")
    println("-".repeat(60))

    for (angle in angles) {
        val pan = calculateStereoPan(angle.toFloat(), 1.0f)

        var dPrime = angle.toFloat()
        while (dPrime > 90f) dPrime = 180f - dPrime
        while (dPrime < -90f) dPrime = -180f - dPrime
        val absDPrime = abs(dPrime)
        val panPosition = absDPrime / 90f

        val powerSum = pan.left * pan.left + pan.right * pan.right

        val notes = when {
            angle == 0 -> "center"
            angle == 90 -> "hard right"
            angle == -90 -> "hard left"
            abs(angle) == 180 -> "behind"
            abs(angle) > 90 -> "back"
            else -> ""
        }

        println("%4d° %5.0f° %5.2f %6.3f %6.3f %6.3f   %s".format(
            angle, dPrime, panPosition, pan.left, pan.right, powerSum, notes
        ))
    }
    println("=" .repeat(60))
}

// ============================================================================
// Tests
// ============================================================================

fun runTests() {
    println("\n╔═══════════════════════════════════════════════════════════╗")
    println("║  Spatial Audio Panning Tests                             ║")
    println("╚═══════════════════════════════════════════════════════════╝")

    // Test 1: Center panning (0°)
    println("\n[Test 1: Center Panning]")
    val pan0 = calculateStereoPan(0f)
    assertFloatEquals(pan0.left, 0.707f, 0.01f, "0° left gain should be ~0.707")
    assertFloatEquals(pan0.right, 0.707f, 0.01f, "0° right gain should be ~0.707")
    val power0 = pan0.left * pan0.left + pan0.right * pan0.right
    assertFloatEquals(power0, 1.0f, 0.01f, "0° constant power (L²+R² ≈ 1.0)")

    // Test 2: Hard right (90°)
    println("\n[Test 2: Hard Right]")
    val pan90 = calculateStereoPan(90f)
    assertFloatEquals(pan90.left, 0.0f, 0.01f, "90° left gain should be 0.0")
    assertFloatEquals(pan90.right, 1.0f, 0.01f, "90° right gain should be 1.0")

    // Test 3: Hard left (-90°)
    println("\n[Test 3: Hard Left]")
    val panN90 = calculateStereoPan(-90f)
    assertFloatEquals(panN90.left, 1.0f, 0.01f, "-90° left gain should be 1.0")
    assertFloatEquals(panN90.right, 0.0f, 0.01f, "-90° right gain should be 0.0")

    // Test 4: Behind symmetry (180° should equal 0° before attenuation)
    println("\n[Test 4: Behind Symmetry]")
    val pan180 = calculateStereoPan(180f, 1.0f)
    assertFloatEquals(pan180.left, 0.707f, 0.01f, "180° left gain should be ~0.707")
    assertFloatEquals(pan180.right, 0.707f, 0.01f, "180° right gain should be ~0.707")

    // Test 5: Front/back symmetry (120° should mirror to 60°)
    println("\n[Test 5: Front/Back Symmetry]")
    val pan60 = calculateStereoPan(60f)
    val pan120 = calculateStereoPan(120f, 1.0f)  // No attenuation to test pure symmetry
    assertFloatEquals(pan60.left, pan120.left, 0.01f, "60° and 120° should have same left gain")
    assertFloatEquals(pan60.right, pan120.right, 0.01f, "60° and 120° should have same right gain")

    // Test 6: Constant power across all angles
    println("\n[Test 6: Constant Power Law]")
    for (angle in listOf(0, 30, 45, 60, 90)) {
        val pan = calculateStereoPan(angle.toFloat())
        val power = pan.left * pan.left + pan.right * pan.right
        assertFloatEquals(power, 1.0f, 0.1f, "$angle° constant power")
    }

    // Test 7: Relative angle calculation
    println("\n[Test 7: Relative Angle Calculation]")
    val rel1 = calculateRelativeAngle(0f, 90f)
    assertFloatEquals(rel1, 90f, 0.1f, "Phone at 0°, source at 90° → relative = 90°")

    val rel2 = calculateRelativeAngle(90f, 0f)
    assertFloatEquals(rel2, -90f, 0.1f, "Phone at 90°, source at 0° → relative = -90°")

    val rel3 = calculateRelativeAngle(0f, 180f)
    assertFloatEquals(rel3, 180f, 0.1f, "Phone at 0°, source at 180° → relative = 180° (or -180°)")
}

// ============================================================================
// Main
// ============================================================================

fun main() {
    // Run automated tests
    runTests()

    // Print reference tables
    printPanTable(
        "Full Range Panning Table (-180° to +180°)",
        listOf(-180, -150, -120, -90, -60, -45, -30, 0, 30, 45, 60, 90, 120, 150, 180)
    )

    printPanTable(
        "Front Hemisphere Only (-90° to +90°)",
        listOf(-90, -75, -60, -45, -30, -15, 0, 15, 30, 45, 60, 75, 90)
    )

    // Summary
    println("\n" + "=".repeat(60))
    println("Test Summary:")
    println("  Passed: $testsPassed")
    println("  Failed: $testsFailed")
    println("=".repeat(60))

    if (testsFailed > 0) {
        exitProcess(1)
    }
}

main()
