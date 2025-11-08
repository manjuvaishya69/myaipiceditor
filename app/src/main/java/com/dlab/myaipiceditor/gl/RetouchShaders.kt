package com.dlab.myaipiceditor.gl

object RetouchShaders {

    const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    // A simple shader that just draws the original image
    const val FRAGMENT_SHADER_PASSTHROUGH = """
        precision mediump float;
        uniform sampler2D uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // Blemish removal shader - uses healing/cloning approach
    const val FRAGMENT_SHADER_BLEMISH = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        void main() {
            vec2 pixelCoord = vTexCoord * uResolution;
            vec2 brushCenter = uBrushCenter * uResolution;
            float dist = distance(pixelCoord, brushCenter);
            float brushSize = uBrushRadius * uResolution.x;
            
            if (dist < brushSize) {
                // Calculate falloff based on hardness
                float falloff = 1.0 - smoothstep(0.0, brushSize, dist);
                falloff = mix(falloff, step(dist, brushSize), uBrushHardness);
                
                // Sample surrounding pixels and blur
                vec4 blurred = vec4(0.0);
                float totalWeight = 0.0;
                int samples = 9;
                float radius = brushSize * 0.3;
                
                for (int i = 0; i < samples; i++) {
                    float angle = float(i) * 6.28318 / float(samples);
                    vec2 offset = vec2(cos(angle), sin(angle)) * radius;
                    vec2 sampleCoord = (pixelCoord + offset) / uResolution;
                    float weight = 1.0;
                    blurred += texture2D(uTexture, sampleCoord) * weight;
                    totalWeight += weight;
                }
                
                blurred /= totalWeight;
                
                vec4 original = texture2D(uTexture, vTexCoord);
                float strength = falloff * uBrushStrength;
                gl_FragColor = mix(original, blurred, strength);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // Smooth skin shader - bilateral filter approach
    const val FRAGMENT_SHADER_SMOOTH = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        void main() {
            vec2 pixelCoord = vTexCoord * uResolution;
            vec2 brushCenter = uBrushCenter * uResolution;
            float dist = distance(pixelCoord, brushCenter);
            float brushSize = uBrushRadius * uResolution.x;
            
            if (dist < brushSize) {
                float falloff = 1.0 - smoothstep(0.0, brushSize, dist);
                falloff = mix(falloff, step(dist, brushSize), uBrushHardness);
                
                // Bilateral blur for skin smoothing
                vec4 sum = vec4(0.0);
                float weightSum = 0.0;
                vec4 centerColor = texture2D(uTexture, vTexCoord);
                
                int kernelSize = 7;
                float sigma = 2.0;
                float sigmaColor = 0.1;
                
                for (int x = -kernelSize/2; x <= kernelSize/2; x++) {
                    for (int y = -kernelSize/2; y <= kernelSize/2; y++) {
                        vec2 offset = vec2(float(x), float(y)) / uResolution;
                        vec4 sample = texture2D(uTexture, vTexCoord + offset);
                        
                        float spatialWeight = exp(-(float(x*x + y*y)) / (2.0 * sigma * sigma));
                        float colorDist = length(sample.rgb - centerColor.rgb);
                        float colorWeight = exp(-(colorDist * colorDist) / (2.0 * sigmaColor * sigmaColor));
                        
                        float weight = spatialWeight * colorWeight;
                        sum += sample * weight;
                        weightSum += weight;
                    }
                }
                
                vec4 smoothed = sum / weightSum;
                float strength = falloff * uBrushStrength;
                gl_FragColor = mix(centerColor, smoothed, strength);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // Skin tone adjustment shader
    const val FRAGMENT_SHADER_SKIN_TONE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        vec3 adjustSkinTone(vec3 color) {
            // Enhance warm tones for natural skin
            float luminance = dot(color, vec3(0.299, 0.587, 0.114));
            vec3 warmer = color * vec3(1.05, 1.0, 0.95);
            warmer = mix(color, warmer, smoothstep(0.2, 0.8, luminance));
            
            // Reduce redness
            float redness = max(0.0, color.r - max(color.g, color.b));
            warmer.r -= redness * 0.3;
            
            return warmer;
        }
        
        void main() {
            vec2 pixelCoord = vTexCoord * uResolution;
            vec2 brushCenter = uBrushCenter * uResolution;
            float dist = distance(pixelCoord, brushCenter);
            float brushSize = uBrushRadius * uResolution.x;
            
            if (dist < brushSize) {
                float falloff = 1.0 - smoothstep(0.0, brushSize, dist);
                falloff = mix(falloff, step(dist, brushSize), uBrushHardness);
                
                vec4 original = texture2D(uTexture, vTexCoord);
                vec3 adjusted = adjustSkinTone(original.rgb);
                
                float strength = falloff * uBrushStrength;
                gl_FragColor = vec4(mix(original.rgb, adjusted, strength), original.a);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // Wrinkle reduction shader - local contrast reduction
    const val FRAGMENT_SHADER_WRINKLE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        void main() {
            vec2 pixelCoord = vTexCoord * uResolution;
            vec2 brushCenter = uBrushCenter * uResolution;
            float dist = distance(pixelCoord, brushCenter);
            float brushSize = uBrushRadius * uResolution.x;
            
            if (dist < brushSize) {
                float falloff = 1.0 - smoothstep(0.0, brushSize, dist);
                falloff = mix(falloff, step(dist, brushSize), uBrushHardness);
                
                vec4 original = texture2D(uTexture, vTexCoord);
                
                // Calculate local average
                vec4 avg = vec4(0.0);
                int samples = 0;
                float sampleRadius = 3.0;
                
                for (float x = -sampleRadius; x <= sampleRadius; x += 1.0) {
                    for (float y = -sampleRadius; y <= sampleRadius; y += 1.0) {
                        vec2 offset = vec2(x, y) / uResolution;
                        avg += texture2D(uTexture, vTexCoord + offset);
                        samples++;
                    }
                }
                avg /= float(samples);
                
                // Reduce local contrast
                vec4 reduced = mix(avg, original, 0.7);
                
                float strength = falloff * uBrushStrength;
                gl_FragColor = mix(original, reduced, strength);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // Teeth whitening shader
    const val FRAGMENT_SHADER_TEETH = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        vec3 whitenTeeth(vec3 color) {
            // Convert to HSL-like space
            float maxC = max(max(color.r, color.g), color.b);
            float minC = min(min(color.r, color.g), color.b);
            float luminance = (maxC + minC) * 0.5;
            
            // Only whiten if it looks like teeth (bright, low saturation)
            float saturation = (maxC - minC) / (maxC + minC + 0.0001);
            
            if (luminance > 0.4 && saturation < 0.3) {
                // Increase brightness and reduce yellow
                vec3 whitened = color;
                whitened.r = min(1.0, color.r * 1.1);
                whitened.g = min(1.0, color.g * 1.1);
                whitened.b = min(1.0, color.b * 1.15); // More blue to counter yellow
                
                // Reduce saturation slightly
                float newLum = (whitened.r + whitened.g + whitened.b) / 3.0;
                whitened = mix(whitened, vec3(newLum), 0.2);
                
                return whitened;
            }
            
            return color;
        }
        
        void main() {
            vec2 pixelCoord = vTexCoord * uResolution;
            vec2 brushCenter = uBrushCenter * uResolution;
            float dist = distance(pixelCoord, brushCenter);
            float brushSize = uBrushRadius * uResolution.x;
            
            if (dist < brushSize) {
                float falloff = 1.0 - smoothstep(0.0, brushSize, dist);
                falloff = mix(falloff, step(dist, brushSize), uBrushHardness);
                
                vec4 original = texture2D(uTexture, vTexCoord);
                vec3 whitened = whitenTeeth(original.rgb);
                
                float strength = falloff * uBrushStrength * 0.6; // Cap strength for natural look
                gl_FragColor = vec4(mix(original.rgb, whitened, strength), original.a);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """
}