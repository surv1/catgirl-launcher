#version 330

// Catgirl Client: Soft Focus. Mixes the sharp picture with a blurred copy:
//  Mode 0 (where you look): sharp at the distance of whatever is in the middle of the screen, softer
//          the further something is in front of or behind it. Your hand always stays sharp.
//  Mode 1 (dreamy edges): sharp in the middle, soft towards the edges.

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D BlurSampler;

layout(std140) uniform FocusConfig {
    float Mode;
    float Amount;
};

in vec2 texCoord;

out vec4 fragColor;

// distance in blocks from the 0..1 depth (the game's near plane is 0.05; its far plane is far
// enough away that treating it as infinitely far is accurate for what we need)
float linearDepth(float d) {
    return 0.05 / max(1.0 - d, 0.00002);
}

float focusDepth() {
    float f = 0.0;
    f += linearDepth(texture(MainDepthSampler, vec2(0.5, 0.5)).r);
    f += linearDepth(texture(MainDepthSampler, vec2(0.47, 0.5)).r);
    f += linearDepth(texture(MainDepthSampler, vec2(0.53, 0.5)).r);
    f += linearDepth(texture(MainDepthSampler, vec2(0.5, 0.46)).r);
    f += linearDepth(texture(MainDepthSampler, vec2(0.5, 0.54)).r);
    return min(f / 5.0, 256.0);
}

void main() {
    vec3 sharp = texture(MainSampler, texCoord).rgb;
    vec3 soft = texture(BlurSampler, texCoord).rgb;
    float k;
    if (Mode < 0.5) {
        float focus = focusDepth();
        float depth = min(linearDepth(texture(MainDepthSampler, texCoord).r), 512.0);
        float off = abs(depth - focus) / (focus * 0.6 + 2.0);
        k = smoothstep(0.12, 1.1, off);
        k *= smoothstep(0.35, 0.9, depth);
    } else {
        vec2 c = texCoord - 0.5;
        c.x *= 1.35;
        k = smoothstep(0.16, 0.6, length(c));
    }
    fragColor = vec4(mix(sharp, soft, clamp(k * Amount, 0.0, 1.0)), 1.0);
}
