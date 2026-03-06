#!/usr/bin/env node

/**
 * NeuralBridge Icon Generator
 * Converts Wave Bridge SVG to PNG at all Android densities
 */

const fs = require('fs');
const path = require('path');
const { createCanvas, loadImage } = require('canvas');

// Wave Bridge SVG (icon #6 from showcase)
const waveBridgeSVG = `
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
`.trim();

// Android density configurations
const densities = [
    { name: 'mdpi', size: 48 },
    { name: 'hdpi', size: 72 },
    { name: 'xhdpi', size: 96 },
    { name: 'xxhdpi', size: 144 },
    { name: 'xxxhdpi', size: 192 }
];

async function generatePNGs() {
    console.log('🎨 NeuralBridge Icon Generator\n');

    // Create output directories
    const baseDir = path.join(__dirname, '..', 'app', 'src', 'main', 'res');

    for (const density of densities) {
        const mipmapDir = path.join(baseDir, `mipmap-${density.name}`);

        // Create directory if it doesn't exist
        if (!fs.existsSync(mipmapDir)) {
            fs.mkdirSync(mipmapDir, { recursive: true });
        }

        // Create canvas
        const canvas = createCanvas(density.size, density.size);
        const ctx = canvas.getContext('2d');

        // Convert SVG to data URL
        const svgBuffer = Buffer.from(waveBridgeSVG);
        const svgDataUrl = `data:image/svg+xml;base64,${svgBuffer.toString('base64')}`;

        try {
            // Load and draw SVG
            const img = await loadImage(svgDataUrl);
            ctx.drawImage(img, 0, 0, density.size, density.size);

            // Save PNG
            const outputPath = path.join(mipmapDir, 'ic_launcher.png');
            const buffer = canvas.toBuffer('image/png');
            fs.writeFileSync(outputPath, buffer);

            console.log(`✅ Generated ${density.name}: ${density.size}×${density.size} → ${outputPath}`);

            // Also create round variant (same file for now)
            const roundPath = path.join(mipmapDir, 'ic_launcher_round.png');
            fs.writeFileSync(roundPath, buffer);
            console.log(`✅ Generated ${density.name} (round): ${density.size}×${density.size} → ${roundPath}`);

        } catch (error) {
            console.error(`❌ Failed to generate ${density.name}:`, error.message);
        }
    }

    console.log('\n🎉 Icon generation complete!');
    console.log('📁 PNG files saved to: android/app/src/main/res/mipmap-*/');
}

// Run generator
generatePNGs().catch(error => {
    console.error('❌ Fatal error:', error);
    process.exit(1);
});
