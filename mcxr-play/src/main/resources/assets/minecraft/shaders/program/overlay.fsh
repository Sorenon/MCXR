#version 100
precision highp float;

uniform sampler2D DiffuseSampler;
uniform sampler2D OverlaySampler;

uniform vec2 InSize;

varying vec2 texCoord;

uniform float MosaicSize;
uniform vec3 RedMatrix;
uniform vec3 GreenMatrix;
uniform vec3 BlueMatrix;



void main(){
    vec2 mosaicInSize = InSize / MosaicSize;
    vec2 fractPix = fract(texCoord * mosaicInSize) / mosaicInSize;

    vec4 baseTexel = texture2D(DiffuseSampler, texCoord - fractPix);
    float red = dot(baseTexel.rgb, RedMatrix);
    float green = dot(baseTexel.rgb, GreenMatrix);
    float blue = dot(baseTexel.rgb, BlueMatrix);

    vec4 overlayTexel = texture2D(OverlaySampler, vec2(texCoord.x, 1.0 - texCoord.y));
    overlayTexel.a = 1.0;
    gl_FragColor = mix(vec4(red, green, blue, 1.0), overlayTexel, overlayTexel.a);
}