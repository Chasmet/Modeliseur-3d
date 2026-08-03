package com.chasmet.modeliseur3d.gl;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import com.chasmet.modeliseur3d.model.MeshData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class ModelRenderer implements android.opengl.GLSurfaceView.Renderer {
    private static final String VERTEX_SHADER =
            "#version 300 es\n"
                    + "uniform mat4 uMvp;\n"
                    + "uniform mat4 uModel;\n"
                    + "in vec3 aPosition;\n"
                    + "in vec3 aNormal;\n"
                    + "in vec2 aTexCoord;\n"
                    + "out vec3 vNormal;\n"
                    + "out vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
                    + "  vNormal = normalize(mat3(uModel) * aNormal);\n"
                    + "  vTexCoord = aTexCoord;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "in vec3 vNormal;\n"
                    + "in vec2 vTexCoord;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "  vec4 texel = texture(uTexture, vTexCoord);\n"
                    + "  if (texel.a < 0.055) { discard; }\n"
                    + "  vec3 normal = normalize(vNormal);\n"
                    + "  vec3 keyLight = normalize(vec3(-0.42, 0.72, 0.82));\n"
                    + "  vec3 fillLight = normalize(vec3(0.68, 0.20, 0.38));\n"
                    + "  float key = max(dot(normal, keyLight), 0.0);\n"
                    + "  float fill = max(dot(normal, fillLight), 0.0);\n"
                    + "  float hemisphere = 0.5 + 0.5 * normal.y;\n"
                    + "  float rim = pow(1.0 - clamp(abs(normal.z), 0.0, 1.0), 2.2);\n"
                    + "  float lighting = 0.42 + 0.42 * key + 0.12 * fill\n"
                    + "      + 0.08 * hemisphere + 0.09 * rim;\n"
                    + "  vec3 linearColor = pow(max(texel.rgb, vec3(0.0)), vec3(2.2));\n"
                    + "  linearColor *= lighting;\n"
                    + "  vec3 finalColor = pow(max(linearColor, vec3(0.0)), vec3(1.0 / 2.2));\n"
                    + "  fragColor = vec4(finalColor, texel.a);\n"
                    + "}\n";

    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] viewModel = new float[16];
    private final float[] mvp = new float[16];

    private int program;
    private int textureId;
    private int indexCount;
    private int surfaceWidth = 1;
    private int surfaceHeight = 1;

    private FloatBuffer positionBuffer;
    private FloatBuffer normalBuffer;
    private FloatBuffer texCoordBuffer;
    private IntBuffer indexBuffer;

    private MeshData pendingMesh;
    private Bitmap pendingTexture;
    private float angleX = -3.0f;
    private float angleY = -12.0f;
    private float zoom = 1.16f;

    @Override
    public void onSurfaceCreated(
            javax.microedition.khronos.opengles.GL10 gl,
            javax.microedition.khronos.egl.EGLConfig config
    ) {
        GLES30.glClearColor(0.055f, 0.066f, 0.092f, 1.0f);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(
                GLES30.GL_SRC_ALPHA,
                GLES30.GL_ONE_MINUS_SRC_ALPHA
        );
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        uploadPendingModelIfNeeded();
    }

    @Override
    public void onSurfaceChanged(
            javax.microedition.khronos.opengles.GL10 gl,
            int width,
            int height
    ) {
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        updateProjection();
    }

    @Override
    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        uploadPendingModelIfNeeded();
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        if (program == 0 || indexBuffer == null
                || textureId == 0 || indexCount == 0) {
            return;
        }

        Matrix.setIdentityM(model, 0);
        Matrix.scaleM(model, 0, zoom, zoom, zoom);
        Matrix.rotateM(model, 0, angleX, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(model, 0, angleY, 0.0f, 1.0f, 0.0f);

        Matrix.setLookAtM(
                view,
                0,
                0.0f,
                0.0f,
                3.85f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                1.0f,
                0.0f
        );
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0);

        GLES30.glUseProgram(program);
        int mvpLocation = GLES30.glGetUniformLocation(program, "uMvp");
        int modelLocation = GLES30.glGetUniformLocation(program, "uModel");
        GLES30.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(modelLocation, 1, false, model, 0);

        int positionLocation = GLES30.glGetAttribLocation(program, "aPosition");
        int normalLocation = GLES30.glGetAttribLocation(program, "aNormal");
        int texCoordLocation = GLES30.glGetAttribLocation(program, "aTexCoord");

        positionBuffer.position(0);
        normalBuffer.position(0);
        texCoordBuffer.position(0);
        indexBuffer.position(0);

        GLES30.glEnableVertexAttribArray(positionLocation);
        GLES30.glVertexAttribPointer(
                positionLocation,
                3,
                GLES30.GL_FLOAT,
                false,
                0,
                positionBuffer
        );
        GLES30.glEnableVertexAttribArray(normalLocation);
        GLES30.glVertexAttribPointer(
                normalLocation,
                3,
                GLES30.GL_FLOAT,
                false,
                0,
                normalBuffer
        );
        GLES30.glEnableVertexAttribArray(texCoordLocation);
        GLES30.glVertexAttribPointer(
                texCoordLocation,
                2,
                GLES30.GL_FLOAT,
                false,
                0,
                texCoordBuffer
        );

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glUniform1i(
                GLES30.glGetUniformLocation(program, "uTexture"),
                0
        );

        GLES30.glDrawElements(
                GLES30.GL_TRIANGLES,
                indexCount,
                GLES30.GL_UNSIGNED_INT,
                indexBuffer
        );

        GLES30.glDisableVertexAttribArray(positionLocation);
        GLES30.glDisableVertexAttribArray(normalLocation);
        GLES30.glDisableVertexAttribArray(texCoordLocation);
    }

    public synchronized void setModel(MeshData mesh, Bitmap texture) {
        pendingMesh = mesh;
        pendingTexture = texture;
    }

    public synchronized void rotate(float deltaX, float deltaY) {
        angleY = clamp(angleY + deltaX * 0.28f, -58.0f, 58.0f);
        angleX = clamp(angleX + deltaY * 0.24f, -35.0f, 35.0f);
    }

    public synchronized void scale(float factor) {
        zoom = clamp(zoom * factor, 0.45f, 2.8f);
    }

    public synchronized void resetView() {
        angleX = -3.0f;
        angleY = -12.0f;
        zoom = 1.16f;
    }

    private synchronized void uploadPendingModelIfNeeded() {
        if (pendingMesh == null || pendingTexture == null || program == 0) {
            return;
        }

        positionBuffer = toFloatBuffer(pendingMesh.getPositions());
        normalBuffer = toFloatBuffer(pendingMesh.getNormals());
        texCoordBuffer = toFloatBuffer(pendingMesh.getTexCoords());
        indexBuffer = toIntBuffer(pendingMesh.getIndices());
        indexCount = pendingMesh.getIndices().length;

        if (textureId != 0) {
            int[] old = {textureId};
            GLES30.glDeleteTextures(1, old, 0);
        }
        int[] textures = new int[1];
        GLES30.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR_MIPMAP_LINEAR
        );
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
        );
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
        );
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
        );
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, pendingTexture, 0);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);

        pendingMesh = null;
        pendingTexture = null;
    }

    private void updateProjection() {
        float ratio = surfaceWidth / (float) surfaceHeight;
        Matrix.perspectiveM(projection, 0, 38.0f, ratio, 0.1f, 100.0f);
    }

    private static FloatBuffer toFloatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private static IntBuffer toIntBuffer(int[] values) {
        IntBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private static int createProgram(
            String vertexSource,
            String fragmentSource
    ) {
        int vertexShader = compileShader(
                GLES30.GL_VERTEX_SHADER,
                vertexSource
        );
        int fragmentShader = compileShader(
                GLES30.GL_FRAGMENT_SHADER,
                fragmentSource
        );
        int result = GLES30.glCreateProgram();
        GLES30.glAttachShader(result, vertexShader);
        GLES30.glAttachShader(result, fragmentShader);
        GLES30.glLinkProgram(result);

        int[] status = new int[1];
        GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String message = GLES30.glGetProgramInfoLog(result);
            GLES30.glDeleteProgram(result);
            throw new IllegalStateException(
                    "Erreur de liaison OpenGL : " + message
            );
        }
        GLES30.glDeleteShader(vertexShader);
        GLES30.glDeleteShader(fragmentShader);
        return result;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String message = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException(
                    "Erreur de shader OpenGL : " + message
            );
        }
        return shader;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
