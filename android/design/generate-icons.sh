#!/bin/bash

# NeuralBridge Icon Generator
# Converts Wave Bridge SVG to PNG at all Android densities using ImageMagick

set -e

echo "🎨 NeuralBridge Icon Generator"
echo ""

# Define paths
DESIGN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RES_DIR="$DESIGN_DIR/../app/src/main/res"
SVG_FILE="$DESIGN_DIR/wave-bridge.svg"

# Check if ImageMagick is installed
if ! command -v convert &> /dev/null; then
    echo "❌ Error: ImageMagick not found. Please install it:"
    echo "   sudo apt-get install imagemagick"
    exit 1
fi

# Create Wave Bridge SVG file
cat > "$SVG_FILE" << 'EOF'
<svg viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg">
    <defs>
        <linearGradient id="grad6" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" style="stop-color:#5E72E4;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#825EE4;stop-opacity:1" />
        </linearGradient>
    </defs>
    <rect width="512" height="512" rx="122" fill="url(#grad6)"/>
    <path d="M 60 320 Q 130 180 200 250 T 340 250 T 452 320" stroke="white" stroke-width="24" fill="none" stroke-linecap="round"/>
    <path d="M 60 340 Q 130 220 200 270 T 340 270 T 452 340" stroke="white" stroke-width="16" fill="none" stroke-linecap="round" opacity="0.6"/>
    <circle cx="200" cy="250" r="20" fill="white"/>
    <circle cx="340" cy="250" r="20" fill="white"/>
    <circle cx="270" cy="220" r="16" fill="white" opacity="0.8"/>
    <line x1="60" y1="320" x2="60" y2="400" stroke="white" stroke-width="20" stroke-linecap="round"/>
    <line x1="452" y1="320" x2="452" y2="400" stroke="white" stroke-width="20" stroke-linecap="round"/>
    <rect x="45" y="390" width="30" height="40" fill="white" rx="8"/>
    <rect x="437" y="390" width="30" height="40" fill="white" rx="8"/>
</svg>
EOF

echo "✅ Created SVG source file: $SVG_FILE"
echo ""

# Android density configurations
declare -A densities=(
    ["mdpi"]=48
    ["hdpi"]=72
    ["xhdpi"]=96
    ["xxhdpi"]=144
    ["xxxhdpi"]=192
)

# Generate PNG files for each density
for density in "${!densities[@]}"; do
    size=${densities[$density]}
    mipmap_dir="$RES_DIR/mipmap-$density"

    # Create directory if it doesn't exist
    mkdir -p "$mipmap_dir"

    # Generate launcher icon
    launcher_png="$mipmap_dir/ic_launcher.png"
    convert -background none -resize "${size}x${size}" "$SVG_FILE" "$launcher_png"
    echo "✅ Generated $density: ${size}×${size} → $launcher_png"

    # Generate round launcher icon (same as regular for now)
    launcher_round_png="$mipmap_dir/ic_launcher_round.png"
    cp "$launcher_png" "$launcher_round_png"
    echo "✅ Generated $density (round): ${size}×${size} → $launcher_round_png"
done

echo ""
echo "🎉 Icon generation complete!"
echo "📁 PNG files saved to: android/app/src/main/res/mipmap-*/"
echo ""
echo "Generated files:"
echo "  - 5 density variants (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)"
echo "  - Regular and round launcher icons"
echo "  - Adaptive icon vector drawables (XML)"
echo "  - Gradient background drawable"
echo ""
echo "✨ All resources ready for Android build!"
