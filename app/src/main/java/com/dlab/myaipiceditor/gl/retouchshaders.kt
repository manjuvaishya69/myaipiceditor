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

    const val FRAGMENT_SHADER_PASSTHROUGH = """
        precision mediump float;
        uniform sampler2D uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """

    // ✅ ENHANCED Blemish removal with smart healing
    const val FRAGMENT_SHADER_BLEMISH = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        // Convert RGB to luminance
        float getLuminance(vec3 color) {
            return dot(color, vec3(0.299, 0.587, 0.114));
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
                
                // Smart healing: sample from surrounding area
                vec4 healed = vec4(0.0);
                float totalWeight = 0.0;
                int samples = 12;
                float sampleRadius = brushSize * 1.2;
                
                for (int i = 0; i < samples; i++) {
                    float angle = float(i) * 6.28318 / float(samples);
                    vec2 offset = vec2(cos(angle), sin(angle)) * sampleRadius;
                    vec2 sampleCoord = (pixelCoord + offset) / uResolution;
                    
                    vec4 sampleColor = texture2D(uTexture, sampleCoord);
                    float lumDiff = abs(getLuminance(original.rgb) - getLuminance(sampleColor.rgb));
                    float weight = exp(-lumDiff * 5.0); // Weight by similarity
                    
                    healed += sampleColor * weight;
                    totalWeight += weight;
                }
                
                if (totalWeight > 0.0) {
                    healed /= totalWeight;
                } else {
                    healed = original;
                }
                
                // Preserve texture while removing blemish
                vec3 result = mix(original.rgb, healed.rgb, falloff * uBrushStrength);
                gl_FragColor = vec4(result, original.a);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // ✅ ENHANCED Smooth skin with detail preservation
    const val FRAGMENT_SHADER_SMOOTH = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        // High-quality bilateral blur for skin smoothing
        vec4 bilateralBlur(vec2 uv, float radius) {
            vec4 centerColor = texture2D(uTexture, uv);
            vec4 sum = vec4(0.0);
            float weightSum = 0.0;
            
            int samples = 5;
            float sigma = radius * 0.5;
            float sigmaSq = sigma * sigma;
            
            for (int x = -samples; x <= samples; x++) {
                for (int y = -samples; y <= samples; y++) {
                    vec2 offset = vec2(float(x), float(y)) / uResolution * radius;
                    vec4 sampleColor = texture2D(uTexture, uv + offset);
                    
                    // Spatial weight
                    float spatialDist = length(vec2(float(x), float(y)));
                    float spatialWeight = exp(-spatialDist * spatialDist / (2.0 * sigmaSq));
                    
                    // Color weight (preserve edges)
                    vec3 colorDiff = sampleColor.rgb - centerColor.rgb;
                    float colorDist = dot(colorDiff, colorDiff);
                    float colorWeight = exp(-colorDist * 25.0);
                    
                    float weight = spatialWeight * colorWeight;
                    sum += sampleColor * weight;
                    weightSum += weight;
                }
            }
            
            return sum / weightSum;
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
                vec4 smoothed = bilateralBlur(vTexCoord, brushSize / uResolution.x);
                
                // Apply smoothing while preserving some original detail
                vec3 result = mix(original.rgb, smoothed.rgb, falloff * uBrushStrength * 0.8);
                gl_FragColor = vec4(result, original.a);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // ✅ ENHANCED Skin tone adjustment with natural warmth
    const val FRAGMENT_SHADER_SKIN_TONE = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        // Convert RGB to HSL
        vec3 rgb2hsl(vec3 color) {
            float maxVal = max(max(color.r, color.g), color.b);
            float minVal = min(min(color.r, color.g), color.b);
            float delta = maxVal - minVal;
            
            float h = 0.0;
            float s = 0.0;
            float l = (maxVal + minVal) / 2.0;
            
            if (delta > 0.0001) {
                s = (l < 0.5) ? delta / (maxVal + minVal) : delta / (2.0 - maxVal - minVal);
                
                if (color.r >= maxVal) {
                    h = (color.g - color.b) / delta;
                } else if (color.g >= maxVal) {
                    h = 2.0 + (color.b - color.r) / delta;
                } else {
                    h = 4.0 + (color.r - color.g) / delta;
                }
                h /= 6.0;
                if (h < 0.0) h += 1.0;
            }
            
            return vec3(h, s, l);
        }
        
        // Convert HSL to RGB
        vec3 hsl2rgb(vec3 hsl) {
            float h = hsl.x;
            float s = hsl.y;
            float l = hsl.z;
            
            float c = (1.0 - abs(2.0 * l - 1.0)) * s;
            float x = c * (1.0 - abs(mod(h * 6.0, 2.0) - 1.0));
            float m = l - c / 2.0;
            
            vec3 rgb;
            if (h < 1.0/6.0) rgb = vec3(c, x, 0.0);
            else if (h < 2.0/6.0) rgb = vec3(x, c, 0.0);
            else if (h < 3.0/6.0) rgb = vec3(0.0, c, x);
            else if (h < 4.0/6.0) rgb = vec3(0.0, x, c);
            else if (h < 5.0/6.0) rgb = vec3(x, 0.0, c);
            else rgb = vec3(c, 0.0, x);
            
            return rgb + m;
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
                vec3 hsl = rgb2hsl(original.rgb);
                
                // Adjust for warmer, more even skin tone
                hsl.x = mix(hsl.x, 0.08, 0.3 * falloff * uBrushStrength); // Shift towards warm orange
                hsl.y = mix(hsl.y, hsl.y * 1.1, 0.2 * falloff * uBrushStrength); // Slight saturation boost
                hsl.z = mix(hsl.z, hsl.z * 1.05, 0.15 * falloff * uBrushStrength); // Subtle brightening
                
                vec3 adjusted = hsl2rgb(hsl);
                gl_FragColor = vec4(adjusted, original.a);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // ✅ ENHANCED Wrinkle reduction with edge-aware smoothing
    const val FRAGMENT_SHADER_WRINKLE = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        // Detect edges (wrinkles)
        float detectEdge(vec2 uv) {
            vec2 pixelSize = 1.0 / uResolution;
            
            vec4 center = texture2D(uTexture, uv);
            vec4 top = texture2D(uTexture, uv + vec2(0.0, pixelSize.y));
            vec4 bottom = texture2D(uTexture, uv - vec2(0.0, pixelSize.y));
            vec4 left = texture2D(uTexture, uv - vec2(pixelSize.x, 0.0));
            vec4 right = texture2D(uTexture, uv + vec2(pixelSize.x, 0.0));
            
            vec3 gx = (right.rgb - left.rgb) * 0.5;
            vec3 gy = (top.rgb - bottom.rgb) * 0.5;
            
            return length(gx) + length(gy);
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
                float edgeStrength = detectEdge(vTexCoord);
                
                // Apply stronger blur to wrinkle areas (edges)
                vec4 blurred = vec4(0.0);
                float totalWeight = 0.0;
                
                int samples = 7;
                for (int x = -samples; x <= samples; x++) {
                    for (int y = -samples; y <= samples; y++) {
                        vec2 offset = vec2(float(x), float(y)) / uResolution;
                        vec4 sampleColor = texture2D(uTexture, vTexCoord + offset);
                        
                        float dist = length(vec2(float(x), float(y)));
                        float weight = exp(-dist * dist / 8.0);
                        
                        blurred += sampleColor * weight;
                        totalWeight += weight;
                    }
                }
                blurred /= totalWeight;
                
                // Blend based on edge strength - more blur on wrinkles
                float blendFactor = mix(0.3, 0.8, edgeStrength * 2.0);
                vec3 result = mix(original.rgb, blurred.rgb, falloff * uBrushStrength * blendFactor);
                
                gl_FragColor = vec4(result, original.a);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """

    // ✅ ENHANCED Teeth whitening with natural results
    const val FRAGMENT_SHADER_TEETH = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uBrushCenter;
        uniform float uBrushRadius;
        uniform float uBrushStrength;
        uniform float uBrushHardness;
        uniform vec2 uResolution;
        varying vec2 vTexCoord;
        
        // Convert RGB to HSV
        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }
        
        // Convert HSV to RGB
        vec3 hsv2rgb(vec3 c) {
            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }
        
        void main() {
            vec2 pixelCoord = vTexCoord * uResolution;
            vec2 brushCenter = uBrushCenter * uResolution;
            float dist = distance(pixelCoord, brushCenter);
            float brushSize = uBrushRadius * uResolution.x;
            
            if (dist < brushSize) {
                float falloff = 1.0 - smoothstep(0.0, brushSize, dist);
                falloff = mix(falloff, step(dist, brushSize), uBrushHardness);
                
                vec4 color = texture2D(uTexture, vTexCoord);
                vec3 hsv = rgb2hsv(color.rgb);
                
                // Remove yellow tint and brighten
                // Reduce saturation (removes yellow)
                hsv.y = mix(hsv.y, hsv.y * 0.5, falloff * uBrushStrength * 0.7);
                
                // Brighten value
                hsv.z = mix(hsv.z, min(hsv.z * 1.2, 1.0), falloff * uBrushStrength * 0.8);
                
                // Shift hue slightly away from yellow
                if (hsv.x > 0.1 && hsv.x < 0.2) { // Yellow range
                    hsv.x = mix(hsv.x, 0.0, falloff * uBrushStrength * 0.3);
                }
                
                vec3 whitened = hsv2rgb(hsv);
                
                // Add subtle blue tint for optical whitening effect
                whitened.b = min(whitened.b + 0.03 * falloff * uBrushStrength, 1.0);
                
                gl_FragColor = vec4(whitened, color.a);
            } else {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        }
    """
}