#!/usr/bin/env python3
"""
WiFi Telescope — Trilaterate WiFi access points using a Raspberry Pi fleet.

Uses log-distance path loss model: RSSI = A - 10*n*log10(d)
where A = RSSI at 1m reference distance, n = path loss exponent.
Then solves for source position using least-squares minimisation.

House Model
===========
Detached house, approx 10m E-W × 8m N-S × 2 floors.
Coordinate system: x=East, y=North, z=Up. Origin at centre of house, ground floor.

                 N (back garden → neighbours' gardens)
                 │
    ┌────────────┼────────────┐
    │ NW         │         NE │
    │            │  springcam │←── window (N-facing)
    │  zeropi    │  deskpi    │
    │  (living)  │  homepi    │
    │            │  (lab)     │
    │←window     │            │
    ├────────────┼────────────┤
    │ SW         │         SE │
    │            │            │
    │  Virgin    │  xoverpi   │
    │  (G2Shub)  │  (music)   │
    │            │            │←── window (S-facing)
    └────────────┼────────────┘  LOWER FLOOR
                 │
    ═════════════╧══════ ROAD
                 │
              Patels (SKYNFSQI)

    ┌────────────┼────────────┐
    │ NW         │         NE │
    │            │  springcam │←── stuck to NE upper window
    │            │  deskpi    │
    │            │  homepi    │
    │            │  (lab)     │
    │            │            │
    ├────────────┼────────────┤
    │ SW         │         SE │
    │            │            │
    │  Virgin    │  pip       │
    │  hub       │  (bedroom) │
    │            │            │←── window (S-facing)
    └────────────┼────────────┘  UPPER FLOOR
                 │
    ═════════════╧══════ ROAD

    West neighbour ←─ gap ─┤ YOU ├─ gap ─→ East neighbour
    (elderly, BT hub)                       (elderly, BT hub)

Receivers
---------
    homepi      Upper NE, back of lab room
    deskpi      Upper NE, near north window (by springcam)
    pip         Upper SE, front east bedroom
    xoverpi     Lower SE, music room (below pip)
    zeropi      Lower NW, living room (faces garden)

Own kit
-------
    3× eero mesh (Grecian2SeymourGdns) — lab eero near homepi, downstairs eero, third unknown
    1× Virgin hub (G2Shub / Grecian2SeymourGuest) — Upper SW

Optics
------
The house acts as a directional antenna / pinhole camera:
    - N windows (back): line of sight across gardens. LinkSprite at ~60m through 2 windows.
    - S windows (front): line of sight across road to Patels. Not along the street.
    - E/W walls: brick, two sets (detached) to each neighbour. High attenuation.
    - Each window is an aperture — only sees what's directly opposite, not at an angle.

Expected external sources
-------------------------
    SKYNFSQI        Patels, south across road. Sky package. Best on pip/xoverpi (front).
    LinkSprite      Neighbour north across gardens, ~60m, through windows.
    BT Hub (east)   Elderly east neighbour. Through 2× brick walls. Weak.
    BT Hub (west)   Elderly west neighbour. Through 2× brick walls. Weak.
"""

import numpy as np
from scipy.optimize import minimize

# House dimensions (metres)
HOUSE_EW = 10.0   # east-west width
HOUSE_NS = 8.0    # north-south depth
FLOOR_H = 3.0     # floor-to-floor height

# Receiver positions in metres (x=east, y=north, z=up)
# Origin at centre of house, ground floor level.
RECEIVERS = {
    "homepi":  np.array([ 3.0,  2.0,  FLOOR_H]),   # Upper NE, back of lab
    "deskpi":  np.array([ 3.0,  3.5,  FLOOR_H]),   # Upper NE, near north window
    "pip":     np.array([ 3.0, -3.5,  FLOOR_H]),   # Upper SE, front bedroom
    "xoverpi": np.array([ 3.0, -3.0,  0.0]),        # Lower SE, music room
    "zeropi":  np.array([-3.0,  2.0,  0.0]),         # Lower NW, living room
}

# RSSI readings per BSSID from each receiver (dBm)
# pip values converted from nmcli percentage: dBm ≈ (percentage/2) - 100
SCANS = {
    # Fresh scan 2026-03-28 ~10:30 (zeropi moved to living room)
    "74:B6:B6:E8:25:85 (Grecian2SeymourGdns/eero pop)": {
        "pip": -31, "homepi": -17, "deskpi": -61, "zeropi": -52, "xoverpi": None,
    },
    "74:B6:B6:E8:B0:65 (Grecian2SeymourGdns/eero)": {
        "pip": -56, "homepi": -41, "deskpi": -43, "zeropi": None, "xoverpi": -66,
    },
    "AC:F8:CC:EA:DB:61 (G2Shub)": {
        "pip": -51, "homepi": -66, "deskpi": -75, "zeropi": -61, "xoverpi": -70,
    },
    "CE:F8:CC:EA:DB:61 (Grecian2SeymourGuest)": {
        "pip": None, "homepi": -66, "deskpi": -73, "zeropi": -61, "xoverpi": -70,
    },
    "1C:64:99:0B:54:30 (Grecian2SeymourGdns/eero)": {
        "pip": -30, "homepi": None, "deskpi": -81, "zeropi": -83, "xoverpi": -51,
    },
    "00:22:6C:37:F5:A9 (hidden/LinkSprite)": {
        "pip": -55, "homepi": None, "deskpi": -43, "zeropi": -75, "xoverpi": -77,
    },
    "74:B6:B6:E8:25:83 (342a60/eero pop)": {
        "pip": -33, "homepi": None, "deskpi": -59, "zeropi": None, "xoverpi": None,
    },
    "90:02:18:55:A8:EC (SKYNFSQI)": {
        "pip": -65, "homepi": -79, "zeropi": -93, "xoverpi": -80,
    },
}


def rssi_to_distance(rssi, A=-30, n=3.0):
    """
    Log-distance path loss model.
    A = RSSI at 1 metre (typical -25 to -35 for WiFi)
    n = path loss exponent (2.0 free space, 2.5-4.0 indoors)
    """
    return 10 ** ((A - rssi) / (10 * n))


def trilaterate(readings, solve_3d=False):
    """
    Find source position that best fits the RSSI readings.
    readings: dict of {receiver_name: rssi_dBm}
    Returns (position, residual)
    """
    receivers = []
    distances = []

    for name, rssi in readings.items():
        if name not in RECEIVERS:
            continue
        receivers.append(RECEIVERS[name])
        distances.append(rssi_to_distance(rssi))

    if len(receivers) < 3:
        return None, None

    receivers = np.array(receivers)
    distances = np.array(distances)

    # Initial guess: weighted average of receiver positions (closer = more weight)
    weights = 1.0 / (distances + 0.1)
    weights /= weights.sum()
    x0 = (receivers * weights[:, np.newaxis]).sum(axis=0)

    def cost(pos):
        """Sum of squared errors between estimated and measured distances."""
        diffs = np.linalg.norm(receivers - pos, axis=1) - distances
        return np.sum(diffs ** 2)

    if not solve_3d:
        # Fix z=0, only solve for x,y
        def cost_2d(pos2d):
            pos = np.array([pos2d[0], pos2d[1], 0.0])
            diffs = np.linalg.norm(receivers - pos, axis=1) - distances
            return np.sum(diffs ** 2)
        result = minimize(cost_2d, x0[:2], method='Nelder-Mead')
        pos = np.array([result.x[0], result.x[1], 0.0])
    else:
        result = minimize(cost, x0, method='Nelder-Mead')
        pos = result.x

    return pos, result.fun


def solve_all(n=3.0):
    """Trilaterate all APs, return list of (label, x, y, residual)."""
    results = []
    for bssid, readings in SCANS.items():
        receivers_list = []
        distances_list = []
        for name, rssi in readings.items():
            if name not in RECEIVERS or rssi is None:
                continue
            receivers_list.append(RECEIVERS[name])
            distances_list.append(rssi_to_distance(rssi, n=n))

        if len(receivers_list) < 3:
            continue

        receivers_arr = np.array(receivers_list)
        distances_arr = np.array(distances_list)

        weights = 1.0 / (distances_arr + 0.1)
        weights /= weights.sum()
        x0 = (receivers_arr * weights[:, np.newaxis]).sum(axis=0)

        def cost_2d(pos2d, recv=receivers_arr, dist=distances_arr):
            pos = np.array([pos2d[0], pos2d[1], 0.0])
            diffs = np.linalg.norm(recv - pos, axis=1) - dist
            return np.sum(diffs ** 2)

        result = minimize(cost_2d, x0[:2], method='Nelder-Mead')
        residual = np.sqrt(result.fun / len(receivers_list))

        # Short label: SSID or last 4 of MAC
        label = bssid.split("(")[-1].rstrip(")") if "(" in bssid else bssid[-5:]
        results.append((label, result.x[0], result.x[1], residual))
    return results


def print_ascii_map(n=3.0, width=100, height=50):
    """Plot the house topology, receivers, and trilaterated APs.

    The house, walls, windows, road and neighbours are the primary features.
    Trilaterated sources are plotted on top.
    """
    aps = solve_all(n)

    # Collect all points for bounds calculation
    points = []  # (x, y, label, kind)
    for name, pos in RECEIVERS.items():
        points.append((pos[0], pos[1], name, 'receiver'))
    for label, x, y, residual in aps:
        points.append((x, y, label, 'ap'))

    # Fixed bounds: show the neighbourhood, not just the data
    # House is 10m E-W (-5 to +5), 8m N-S (-4 to +4)
    # Show ~30m each direction to capture neighbours and Patels
    x_min, x_max = -20.0, 60.0
    y_min, y_max = -40.0, 20.0

    x_range = x_max - x_min
    y_range = y_max - y_min

    # Build grid
    grid = [[' '] * width for _ in range(height)]

    def plot(x, y):
        """Convert world coords to grid col, row. Returns (col, row) or None."""
        col = int((x - x_min) / x_range * (width - 1))
        row = int((y_max - y) / y_range * (height - 1))
        if 0 <= col < width and 0 <= row < height:
            return col, row
        return None

    def draw_hline(y, x1, x2, char='─'):
        """Draw horizontal line from x1 to x2 at world y."""
        p1 = plot(x1, y)
        p2 = plot(x2, y)
        if p1 and p2:
            for c in range(p1[0], p2[0] + 1):
                if 0 <= c < width:
                    grid[p1[1]][c] = char

    def draw_vline(x, y1, y2, char='│'):
        """Draw vertical line from y1 to y2 at world x."""
        p_top = plot(x, max(y1, y2))
        p_bot = plot(x, min(y1, y2))
        if p_top and p_bot:
            col = p_top[0]
            for r in range(p_top[1], p_bot[1] + 1):
                if 0 <= r < height:
                    grid[r][col] = char

    def draw_rect(x1, y1, x2, y2, wall='█', window='░'):
        """Draw a rectangle outline. x1<x2, y1<y2 (y1=south, y2=north)."""
        # Top (north wall)
        draw_hline(y2, x1, x2, wall)
        # Bottom (south wall)
        draw_hline(y1, x1, x2, wall)
        # Left (west wall)
        draw_vline(x1, y1, y2, wall)
        # Right (east wall)
        draw_vline(x2, y1, y2, wall)
        # Corners
        for cx, cy in [(x1,y1), (x1,y2), (x2,y1), (x2,y2)]:
            p = plot(cx, cy)
            if p:
                grid[p[1]][p[0]] = wall

    def place_text(x, y, text):
        """Place a text label at world coords."""
        p = plot(x, y)
        if p:
            col, row = p
            for i, ch in enumerate(text):
                if 0 <= col + i < width:
                    grid[row][col + i] = ch

    def place_window(x, y):
        """Mark a window position."""
        p = plot(x, y)
        if p:
            grid[p[1]][p[0]] = '░'

    # === Draw the neighbourhood ===

    # Road (south of house at y ≈ -6)
    road_y = -6.0
    draw_hline(road_y, x_min, x_max, '═')
    draw_hline(road_y - 2, x_min, x_max, '═')
    place_text(-15.0, road_y - 1, 'R O A D')

    # House outline: 10m E-W (-5 to +5), 8m N-S (-4 to +4)
    draw_rect(-5, -4, 5, 4, '█')
    # Internal divider: E-W at y=0 (upstairs landing / downstairs hall)
    draw_hline(0, -5, 5, '▄')
    # Internal divider: N-S centre line
    draw_vline(0, -4, 4, '▌')

    # Windows — these are the apertures
    # North windows (back, facing garden)
    place_window(-2, 4)    # NW living room window
    place_window(3, 4)     # NE lab/springcam window

    # South windows (front, facing road)
    place_window(-2, -4)   # SW front window
    place_window(3, -4)    # SE front window

    # Room labels
    place_text(-4.5, 2.0, 'living')
    place_text(1.0, 2.0, 'lab')
    place_text(-4.5, -2.0, 'music')  # actually this is confusing with floors
    # The map is a plan view combining both floors

    # West neighbour
    draw_rect(-18, -4, -8, 4, '▒')
    place_text(-17, 0, 'W neighbour')
    place_text(-17, -1, '(elderly/BT)')

    # East neighbour
    draw_rect(8, -4, 18, 4, '▒')
    place_text(9, 0, 'E neighbour')
    place_text(9, -1, '(elderly/BT)')

    # Patels label (south of road)
    place_text(-3, -12, 'Patels (Sky)')

    # Garden label (north)
    place_text(-4, 10, '~~ gardens ~~')
    place_text(-8, 16, 'LinkSprite ~60m N')

    # === Place receivers ===
    receiver_chars = {
        'homepi': 'H', 'deskpi': 'D', 'pip': 'P',
        'xoverpi': 'X', 'zeropi': 'Z',
    }
    legend = []
    for name, pos in RECEIVERS.items():
        char = receiver_chars.get(name, '#')
        p = plot(pos[0], pos[1])
        if p:
            grid[p[1]][p[0]] = char
        legend.append(f"  {char} = {name} ({pos[0]:+.0f},{pos[1]:+.0f},{'upper' if pos[2]>1 else 'lower'})")

    # === Place trilaterated APs ===
    ap_markers = '123456789abcdefghijklmnopqrstuvwxyz'
    legend.append("")
    for i, (label, x, y, residual) in enumerate(aps):
        char = ap_markers[i % len(ap_markers)]
        p = plot(x, y)
        if p:
            grid[p[1]][p[0]] = char
        in_out = "IN" if -5 <= x <= 5 and -4 <= y <= 4 else "out"
        legend.append(f"  {char} = {label} ({x:+.1f},{y:+.1f}) err={residual:.1f}m [{in_out}]")

    # Print
    print()
    print(f"  WiFi Telescope — Neighbourhood Map (n={n})")
    print(f"  x=East  y=North  [{x_range:.0f}m × {y_range:.0f}m]")
    print(f"  ┌{'─' * width}┐")
    for row in grid:
        print(f"  │{''.join(row)}│")
    print(f"  └{'─' * width}┘")
    print()
    print("  Receivers:")
    for line in legend:
        print(line)
    print()
    print("  █ = brick wall   ░ = window (aperture)   ▒ = neighbour's house")
    print()


def main():
    print("WiFi AP Trilateration")
    print("=" * 70)
    print()
    print("Receiver positions:")
    for name, pos in RECEIVERS.items():
        print(f"  {name:10s}  ({pos[0]:+5.1f}, {pos[1]:+5.1f}, {pos[2]:+5.1f})")
    print()

    # Try different path loss parameters
    for n in [2.5, 3.0, 3.5]:
        print(f"--- Path loss exponent n={n} ---")
        print(f"{'BSSID':<45s}  {'x':>6s} {'y':>6s}  {'dist_err':>8s}  nearest receiver")
        print("-" * 90)

        for bssid, readings in SCANS.items():
            # Temporarily patch the module-level function
            orig = globals().get('_n_override', None)

            # Inline distance calc with this n
            receivers_list = []
            distances_list = []
            for name, rssi in readings.items():
                if name not in RECEIVERS or rssi is None:
                    continue
                receivers_list.append((name, RECEIVERS[name]))
                distances_list.append(rssi_to_distance(rssi, n=n))

            if len(receivers_list) < 3:
                print(f"{bssid:<45s}  (not enough receivers)")
                continue

            receivers_arr = np.array([r[1] for r in receivers_list])
            distances_arr = np.array(distances_list)

            weights = 1.0 / (distances_arr + 0.1)
            weights /= weights.sum()
            x0 = (receivers_arr * weights[:, np.newaxis]).sum(axis=0)

            def cost_2d(pos2d, recv=receivers_arr, dist=distances_arr):
                pos = np.array([pos2d[0], pos2d[1], 0.0])
                diffs = np.linalg.norm(recv - pos, axis=1) - dist
                return np.sum(diffs ** 2)

            result = minimize(cost_2d, x0[:2], method='Nelder-Mead')
            pos = result.x
            residual = np.sqrt(result.fun / len(receivers_list))

            # Find nearest receiver
            nearest = min(RECEIVERS.items(),
                         key=lambda r: np.linalg.norm(np.array([pos[0], pos[1]]) - r[1][:2]))
            nearest_dist = np.linalg.norm(np.array([pos[0], pos[1]]) - nearest[1][:2])

            print(f"{bssid:<45s}  {pos[0]:+6.1f} {pos[1]:+6.1f}  {residual:8.1f}m  "
                  f"{nearest[0]} ({nearest_dist:.1f}m away)")

        print()

    # ASCII map at n=3.0
    print_ascii_map(3.0)

    # Show estimated distances for validation
    print("=" * 70)
    print("Estimated distances (metres) from each receiver to each AP (n=3.0):")
    print(f"{'BSSID':<45s}", end="")
    for name in RECEIVERS:
        print(f"  {name:>7s}", end="")
    print()
    print("-" * 90)
    for bssid, readings in SCANS.items():
        print(f"{bssid:<45s}", end="")
        for name in RECEIVERS:
            rssi = readings.get(name)
            if rssi is not None:
                d = rssi_to_distance(rssi, n=3.0)
                print(f"  {d:7.1f}", end="")
            else:
                print(f"  {'—':>7s}", end="")
        print()


if __name__ == "__main__":
    main()
