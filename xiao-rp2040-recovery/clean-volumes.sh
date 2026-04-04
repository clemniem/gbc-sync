#!/bin/bash
# Prevent and clean macOS metadata (.DS_Store, .Spotlight-V100, .fseventsd, .Trashes)
# on all mounted external volumes (everything under /Volumes except Macintosh HD).

set -e

echo "=== macOS External Volume Cleaner ==="
echo ""

# Step 1: Global settings
echo "[1/4] Setting global defaults..."
defaults write com.apple.desktopservices DSDontWriteNetworkStores -bool true
defaults write com.apple.desktopservices DSDontWriteUSBStores -bool true
echo "  DSDontWriteUSBStores: $(defaults read com.apple.desktopservices DSDontWriteUSBStores 2>/dev/null)"
echo "  DSDontWriteNetworkStores: $(defaults read com.apple.desktopservices DSDontWriteNetworkStores 2>/dev/null)"

# Step 2: Disable Spotlight for /Volumes
echo ""
echo "[2/4] Disabling Spotlight indexing on /Volumes..."
sudo mdutil -i off /Volumes 2>/dev/null || true

# Step 3: Clean and protect each external volume
echo ""
echo "[3/4] Cleaning external volumes..."

for vol in /Volumes/*/; do
    vol="${vol%/}"
    name="$(basename "$vol")"

    # Skip the boot volume
    if [ "$name" = "Macintosh HD" ] || [ "$name" = "Macintosh HD - Data" ]; then
        continue
    fi

    echo ""
    echo "  --- $name ---"

    # Disable spotlight for this volume
    sudo mdutil -i off "$vol" 2>/dev/null || true
    sudo mdutil -E "$vol" 2>/dev/null || true

    # Remove macOS junk
    for junk in .DS_Store .Spotlight-V100 .Trashes .fseventsd; do
        if [ -e "$vol/$junk" ]; then
            sudo rm -rf "$vol/$junk" 2>/dev/null && echo "  Removed $junk" || echo "  Could not remove $junk (SIP?)"
        fi
    done

    # Prevent Spotlight from re-indexing
    touch "$vol/.metadata_never_index" 2>/dev/null || true

    # Prevent fseventsd logging
    mkdir -p "$vol/.fseventsd" 2>/dev/null || true
    touch "$vol/.fseventsd/no_log" 2>/dev/null || true
    echo "  Protected with .metadata_never_index and .fseventsd/no_log"
done

# Step 4: Verify
echo ""
echo "[4/4] Verification..."
echo ""

clean=true
for vol in /Volumes/*/; do
    vol="${vol%/}"
    name="$(basename "$vol")"
    if [ "$name" = "Macintosh HD" ] || [ "$name" = "Macintosh HD - Data" ]; then
        continue
    fi

    issues=""
    [ -d "$vol/.Spotlight-V100" ] && issues="$issues .Spotlight-V100"
    [ -f "$vol/.DS_Store" ] && issues="$issues .DS_Store"
    [ -d "$vol/.Trashes" ] && issues="$issues .Trashes"

    if [ -n "$issues" ]; then
        echo "  WARN: $name still has:$issues"
        clean=false
    else
        echo "  OK: $name is clean"
    fi
done

echo ""
if $clean; then
    echo "All external volumes are clean."
else
    echo "Some files could not be removed (likely SIP-protected)."
    echo "Try: unmount the volume, re-mount, then run this script again."
fi
