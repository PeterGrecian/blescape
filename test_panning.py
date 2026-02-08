#!/usr/bin/env python3
"""
Command-line test program for spatial audio panning calculations.

Usage:
    python3 test_panning.py

References:
- Constant-power panning: https://www.cs.cmu.edu/~music/icm-online/readings/panlaws/
- Stereo imaging: https://cmtext.indiana.edu/synthesis/chapter4_panning.php
- Square-root law: https://dsp.stackexchange.com/questions/21691/
"""

import math
import sys

# ============================================================================
# Core Panning Logic (ported from SpatialMixer.kt)
# ============================================================================

class StereoPan:
    def __init__(self, left, right):
        self.left = left
        self.right = right

def calculate_relative_angle(phone_azimuth, device_world_azimuth):
    """Calculate relative angle between listener and source."""
    phone_rad = math.radians(phone_azimuth)
    device_rad = math.radians(device_world_azimuth)
    delta_rad = device_rad - phone_rad
    normalized_rad = math.atan2(math.sin(delta_rad), math.cos(delta_rad))
    return math.degrees(normalized_rad)

def calculate_stereo_pan(relative_angle, behind_attenuation=1.0):
    """Calculate stereo pan gains using constant-power panning."""
    # Apply front/back symmetry
    d_prime = relative_angle
    while d_prime > 90:
        d_prime = 180 - d_prime
    while d_prime < -90:
        d_prime = -180 - d_prime

    # Calculate pan position
    abs_d_prime = abs(d_prime)
    pan_position = abs_d_prime / 90.0

    # Constant-power panning law (square-root)
    right_gain = math.sqrt((pan_position + 1) / 2)
    left_gain = math.sqrt((1 - pan_position) / 2)

    # Swap L/R for left side
    if d_prime < 0:
        left_gain, right_gain = right_gain, left_gain

    # Apply behind attenuation
    attenuation = behind_attenuation if abs(relative_angle) > 90 else 1.0

    return StereoPan(left_gain * attenuation, right_gain * attenuation)

# ============================================================================
# Test Utilities
# ============================================================================

tests_passed = 0
tests_failed = 0

def assert_test(condition, message):
    global tests_passed, tests_failed
    if condition:
        tests_passed += 1
        print(f"✓ PASS: {message}")
    else:
        tests_failed += 1
        print(f"✗ FAIL: {message}")

def assert_float_equals(actual, expected, tolerance, message):
    global tests_passed, tests_failed
    diff = abs(actual - expected)
    if diff <= tolerance:
        tests_passed += 1
        print(f"✓ PASS: {message} (actual={actual:.3f}, expected={expected:.3f})")
    else:
        tests_failed += 1
        print(f"✗ FAIL: {message} (actual={actual:.3f}, expected={expected:.3f}, diff={diff:.3f})")

def print_pan_table(title, angles):
    """Print formatted panning table."""
    print(f"\n{title}")
    print("=" * 60)
    print("Angle    d'    Pan    Left   Right   L²+R²   Notes")
    print("-" * 60)

    for angle in angles:
        pan = calculate_stereo_pan(angle, 1.0)

        # Calculate d'
        d_prime = float(angle)
        while d_prime > 90:
            d_prime = 180 - d_prime
        while d_prime < -90:
            d_prime = -180 - d_prime

        abs_d_prime = abs(d_prime)
        pan_position = abs_d_prime / 90.0
        power_sum = pan.left**2 + pan.right**2

        notes = ""
        if angle == 0:
            notes = "center"
        elif angle == 90:
            notes = "hard right"
        elif angle == -90:
            notes = "hard left"
        elif abs(angle) == 180:
            notes = "behind"
        elif abs(angle) > 90:
            notes = "back"

        print(f"{angle:4d}° {d_prime:5.0f}° {pan_position:5.2f} {pan.left:6.3f} {pan.right:6.3f} {power_sum:6.3f}   {notes}")

    print("=" * 60)

# ============================================================================
# Tests
# ============================================================================

def run_tests():
    print("\n╔═══════════════════════════════════════════════════════════╗")
    print("║  Spatial Audio Panning Tests                             ║")
    print("╚═══════════════════════════════════════════════════════════╝")

    # Test 1: Center panning (0°)
    print("\n[Test 1: Center Panning]")
    pan0 = calculate_stereo_pan(0)
    assert_float_equals(pan0.left, 0.707, 0.01, "0° left gain should be ~0.707")
    assert_float_equals(pan0.right, 0.707, 0.01, "0° right gain should be ~0.707")
    power0 = pan0.left**2 + pan0.right**2
    assert_float_equals(power0, 1.0, 0.01, "0° constant power (L²+R² ≈ 1.0)")

    # Test 2: Hard right (90°)
    print("\n[Test 2: Hard Right]")
    pan90 = calculate_stereo_pan(90)
    assert_float_equals(pan90.left, 0.0, 0.01, "90° left gain should be 0.0")
    assert_float_equals(pan90.right, 1.0, 0.01, "90° right gain should be 1.0")

    # Test 3: Hard left (-90°)
    print("\n[Test 3: Hard Left]")
    pan_n90 = calculate_stereo_pan(-90)
    assert_float_equals(pan_n90.left, 1.0, 0.01, "-90° left gain should be 1.0")
    assert_float_equals(pan_n90.right, 0.0, 0.01, "-90° right gain should be 0.0")

    # Test 4: Behind symmetry (180° should equal 0° before attenuation)
    print("\n[Test 4: Behind Symmetry]")
    pan180 = calculate_stereo_pan(180, 1.0)
    assert_float_equals(pan180.left, 0.707, 0.01, "180° left gain should be ~0.707")
    assert_float_equals(pan180.right, 0.707, 0.01, "180° right gain should be ~0.707")

    # Test 5: Front/back symmetry (120° should mirror to 60°)
    print("\n[Test 5: Front/Back Symmetry]")
    pan60 = calculate_stereo_pan(60)
    pan120 = calculate_stereo_pan(120, 1.0)
    assert_float_equals(pan60.left, pan120.left, 0.01, "60° and 120° should have same left gain")
    assert_float_equals(pan60.right, pan120.right, 0.01, "60° and 120° should have same right gain")

    # Test 6: Constant power across all angles
    print("\n[Test 6: Constant Power Law]")
    for angle in [0, 30, 45, 60, 90]:
        pan = calculate_stereo_pan(angle)
        power = pan.left**2 + pan.right**2
        assert_float_equals(power, 1.0, 0.1, f"{angle}° constant power")

    # Test 7: Relative angle calculation
    print("\n[Test 7: Relative Angle Calculation]")
    rel1 = calculate_relative_angle(0, 90)
    assert_float_equals(rel1, 90, 0.1, "Phone at 0°, source at 90° → relative = 90°")

    rel2 = calculate_relative_angle(90, 0)
    assert_float_equals(rel2, -90, 0.1, "Phone at 90°, source at 0° → relative = -90°")

    rel3 = calculate_relative_angle(0, 180)
    assert_float_equals(abs(rel3), 180, 0.1, "Phone at 0°, source at 180° → relative = ±180°")

# ============================================================================
# Main
# ============================================================================

def main():
    # Run automated tests
    run_tests()

    # Print reference tables
    print_pan_table(
        "Full Range Panning Table (-180° to +180°)",
        [-180, -150, -120, -90, -60, -45, -30, 0, 30, 45, 60, 90, 120, 150, 180]
    )

    print_pan_table(
        "Front Hemisphere Only (-90° to +90°)",
        [-90, -75, -60, -45, -30, -15, 0, 15, 30, 45, 60, 75, 90]
    )

    # Summary
    print("\n" + "=" * 60)
    print("Test Summary:")
    print(f"  Passed: {tests_passed}")
    print(f"  Failed: {tests_failed}")
    print("=" * 60)

    if tests_failed > 0:
        sys.exit(1)

if __name__ == "__main__":
    main()
