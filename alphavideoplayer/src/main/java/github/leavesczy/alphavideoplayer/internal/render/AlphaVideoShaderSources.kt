package github.leavesczy.alphavideoplayer.internal.render

internal object AlphaVideoShaderSources {

    const val VERTEX = """
uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;

attribute vec4 aPosition;
attribute vec4 aTextureCoord;
attribute vec4 aAlphaTextureCoord;

varying vec2 vTextureCoord;
varying vec2 vAlphaTextureCoord;

void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
    vAlphaTextureCoord = (uSTMatrix * aAlphaTextureCoord).xy;
}
"""

    /** RGB from the color UV. Alpha is the green channel of the alpha half. */
    const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;
varying vec2 vAlphaTextureCoord;
uniform samplerExternalOES sTexture;

void main() {
    vec4 color = texture2D(sTexture, vTextureCoord);
    float alpha = texture2D(sTexture, vAlphaTextureCoord).g;
    gl_FragColor = vec4(color.r, color.g, color.b, alpha);
}
"""
}
