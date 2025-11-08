// Barrel Blur Chroma Filter
#ifdef GL_ES
precision mediump float;
#endif

// *** CHANGED *** Use "uTexture" to match the handle we get in Kotlin
uniform sampler2D uTexture;
uniform vec2 resolution;   // This will be set by glUniform2f
uniform float intensity;   // This will be set by glUniform1f
varying vec2 vTexCoord;    // Still useful for some effects, but not this one

// Constants
const int num_iter = 12;
const float reci_num_iter_f = 1.0 / float(num_iter);

// ... (rest of the functions: barrelDistortion, sat, linterp, remap, spectrum_offset) ...
vec2 barrelDistortion(vec2 coord, float amt) {
vec2 cc = coord - 0.5;
float dist = dot(cc, cc);
return coord + cc * dist * amt;
}
float sat(float t) {
    return clamp(t, 0.0, 1.0);
}
float linterp(float t) {
    return sat(1.0 - abs(2.0 * t - 1.0));
}
float remap(float t, float a, float b) {
    return sat((t - a) / (b - a));
}
vec3 spectrum_offset(float t) {
    vec3 ret;
    float lo = step(t, 0.5);
    float hi = 1.0 - lo;
    float w = linterp(remap(t, 1.0/6.0, 5.0/6.0));
    ret = vec3(lo, 1.0, hi) * vec3(1.0 - w, w, 1.0 - w);
    return pow(ret, vec3(1.0 / 2.2));
}

void main() {
    // *** CHANGED *** This now works correctly!
    vec2 uv = gl_FragCoord.xy / resolution.xy;

    float barrelPower = 0.1 * intensity;

    vec3 sumcol = vec3(0.0);
    vec3 sumw = vec3(0.0);

    for (int i = 0; i < num_iter; ++i) {
        float t = float(i) * reci_num_iter_f;
        vec3 w = spectrum_offset(t);
        sumw += w;
        // *** CHANGED *** Use "uTexture"
        sumcol += w * texture2D(uTexture, barrelDistortion(uv, barrelPower * t)).rgb;
    }

    vec3 color = sumcol / sumw;
    gl_FragColor = vec4(color, 1.0);
}