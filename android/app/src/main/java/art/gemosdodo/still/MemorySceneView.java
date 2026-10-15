package art.gemosdodo.still;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native GLES 3 memory terminal. The housing is the original product mesh;
 * memories are anisotropic, depth-sorted 3D Gaussian splats, not GL_POINTS.
 * All scene compositing is linear RGB; the display transform happens once.
 */
public final class MemorySceneView extends GLSurfaceView {
    private final SceneRenderer scene;
    private final ScaleGestureDetector scaleDetector;
    private boolean attached, appPaused, inferencePaused;
    private float lastX, lastY;
    private boolean multiTouch;
    private long lastFrame;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!attached || appPaused || inferencePaused) return;
            long now = SystemClock.uptimeMillis();
            if (scene.needsAnimation && now - lastFrame >= 31) { requestRender(); lastFrame = now; }
            postOnAnimation(this);
        }
    };

    public MemorySceneView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        setPreserveEGLContextOnPause(true);
        scene = new SceneRenderer(context.getApplicationContext());
        setRenderer(scene);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setContentDescription("3D 记忆盒。单指旋转，双指缩放。");
        setFocusable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                final float factor = detector.getScaleFactor();
                queueEvent(() -> scene.zoom(factor));
                requestRender();
                return true;
            }
        });
    }

    /** 16 little-endian floats per record: position, alpha, covariance, RGB. */
    public void loadMemory(float[] records) {
        if (records == null || records.length == 0 || records.length % 16 != 0)
            throw new IllegalArgumentException("记忆数据不完整");
        if (records.length / 16 > 1500000) throw new IllegalArgumentException("记忆文件过大");
        final float[] owned = records.clone();
        queueEvent(() -> { try { scene.load(owned); } catch (Throwable e) { scene.reportFailure("Memory upload", e); } });
        requestRender();
    }

    public void clearMemory() { queueEvent(scene::clear); requestRender(); }
    public void front() { queueEvent(scene::front); requestRender(); }
    public void reset() { queueEvent(scene::reset); requestRender(); }

    /** Read-only diagnostics for device smoke checks; safe from the UI thread. */
    public String diagnostics() {
        try {
            JSONObject report = new JSONObject();
            report.put("renderer", "native-gles3-gaussian");
            report.put("ready", scene.readySnapshot);
            report.put("frameCount", scene.frameCount);
            report.put("particleCount", scene.particleCount);
            report.put("partCount", scene.partCount);
            report.put("memoryLoaded", scene.memoryLoaded);
            report.put("maxTextureSize", scene.maxTextureSize);
            report.put("gpu", scene.gpu);
            report.put("glVersion", scene.glVersion);
            report.put("glError", scene.lastGlError);
            report.put("renderError", scene.renderError);
            report.put("error", scene.renderError);
            return report.toString();
        } catch (Exception ignored) { return "{\"renderError\":\"diagnostics unavailable\"}"; }
    }

    /** Stop the view's frame pump while on-device inference needs memory/thermal headroom. */
    public void setPaused(boolean paused) {
        inferencePaused = paused;
        removeCallbacks(frame);
        if (!paused && attached && !appPaused) postOnAnimation(frame);
    }

    @Override public void onPause() {
        appPaused = true;
        removeCallbacks(frame);
        super.onPause();
    }

    @Override public void onResume() {
        super.onResume();
        appPaused = false;
        removeCallbacks(frame);
        if (attached && !inferencePaused) postOnAnimation(frame);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true;
        if (!appPaused && !inferencePaused) postOnAnimation(frame);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false; removeCallbacks(frame); super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (inferencePaused) return true;
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX = event.getX(); lastY = event.getY(); multiTouch = false;
                queueEvent(() -> scene.dragging = true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true; return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !multiTouch && !scaleDetector.isInProgress()) {
                    float density = getResources().getDisplayMetrics().density;
                    final float dx = (event.getX() - lastX) / density;
                    final float dy = (event.getY() - lastY) / density;
                    queueEvent(() -> scene.rotate(dx, dy));
                    requestRender();
                }
                lastX = event.getX(); lastY = event.getY();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                multiTouch = true; return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                queueEvent(() -> scene.dragging = false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private static final class Mesh {
        int buffer, count, texture, kind;
        float intensity = 1;
        boolean transparent, floor;
        Mesh(int b, int c) { buffer = b; count = c; }
    }

    private static final class SceneRenderer implements GLSurfaceView.Renderer {
        static final String TAG = "GemosScene";
        final Context context;
        final ArrayList<Mesh> housing = new ArrayList<>(), decals = new ArrayList<>();
        final HashMap<String,Integer> textureCache = new HashMap<>();
        final float[] projection = new float[16], view = new float[16], inverseView = new float[16];
        final float[] eye = new float[3];
        final int[] histogram = new int[65536];
        int meshProgram, gaussianProgram, glassProgram, outputProgram;
        int width = 1, height = 1, framebuffer, colorTexture, depthBuffer;
        int gaussianTexture, orderBuffer, quadBuffer, emptyVao;
        Mesh floorMesh, glassMesh;
        float[] data, idleData, order, depths;
        int[] buckets;
        FloatBuffer orderFloats;
        int count, textureHeight;
        boolean loaded, ready, dirtyOrder = true, dragging;
        float yaw = .608f, pitch = .395f, radius = 14.9f;
        float targetYaw = yaw, targetPitch = pitch, targetRadius = radius;
        float lastSortX = 100, lastSortY = 100, lastSortZ = 100;
        long previousFrame, began = SystemClock.uptimeMillis(), revealStart;
        String failure;
        volatile boolean needsAnimation = true, readySnapshot, memoryLoaded;
        volatile long frameCount;
        volatile int particleCount, partCount, maxTextureSize, lastGlError;
        volatile String renderError = "", gpu = "", glVersion = "";

        SceneRenderer(Context context) { this.context = context; }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            ready = false; readySnapshot = false; failure = null; renderError = "";
            housing.clear(); decals.clear(); textureCache.clear();
            framebuffer = colorTexture = depthBuffer = gaussianTexture = 0;
            try {
                meshProgram = program(MESH_VERTEX, MESH_FRAGMENT);
                gaussianProgram = program(GAUSSIAN_VERTEX, GAUSSIAN_FRAGMENT);
                glassProgram = program(GLASS_VERTEX, GLASS_FRAGMENT);
                outputProgram = program(OUTPUT_VERTEX, OUTPUT_FRAGMENT);
                int[] id = new int[1]; GLES30.glGenVertexArrays(1, id, 0); emptyVao = id[0];
                GLES30.glBindVertexArray(emptyVao);
                quadBuffer = buffer(new float[]{-1,-1, 1,-1, -1,1, 1,1}, GLES30.GL_STATIC_DRAW);
                GLES30.glGenBuffers(1, id, 0); orderBuffer = id[0];
                loadHousing();
                floorMesh = ground(); glassMesh = glass();
                if (data == null) { idleData = makeIdle(); data = idleData; loaded = false; }
                uploadGaussians();
                GLES30.glEnable(GLES30.GL_DEPTH_TEST);
                GLES30.glDepthFunc(GLES30.GL_LEQUAL);
                GLES30.glDisable(GLES30.GL_CULL_FACE);
                GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, id, 0); maxTextureSize = id[0];
                gpu = GLES30.glGetString(GLES30.GL_RENDERER);
                glVersion = GLES30.glGetString(GLES30.GL_VERSION);
                partCount = housing.size();
                ready = true; readySnapshot = true; needsAnimation = true;
                Log.i(TAG, "Native GLES scene ready: " + housing.size() + " housing parts, " + count + " Gaussian splats; " + GLES30.glGetString(GLES30.GL_RENDERER));
            } catch (Throwable e) { reportFailure("Scene initialization", e); }
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            this.width = Math.max(1, width); this.height = Math.max(1, height);
            // A bounded offscreen target keeps the native view inexpensive on 3K phones.
            try { createFramebuffer(); dirtyOrder = true; needsAnimation = true; }
            catch (Throwable e) { reportFailure("Framebuffer", e); }
        }

        @Override public void onDrawFrame(GL10 gl) {
            if (!ready) { GLES30.glClearColor(.04f,.04f,.04f,1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return; }
            try { draw(); } catch (Throwable e) { reportFailure("Frame rendering", e); }
        }

        void draw() {
            final long now = SystemClock.uptimeMillis();
            float dt = previousFrame == 0 ? .033f : Math.min(.065f, (now - previousFrame) * .001f);
            previousFrame = now;
            float damping = 1 - (float)Math.exp(-dt * (dragging ? 17 : 7));
            yaw += shortestAngle(targetYaw - yaw) * damping;
            pitch += (targetPitch - pitch) * damping;
            radius += (targetRadius - radius) * damping;
            float aspect = width / (float)height;
            float distance = radius * Math.max(1, .82f / aspect);
            eye[0] = (float)(Math.sin(yaw) * Math.cos(pitch)) * distance;
            eye[1] = .1f + (float)Math.sin(pitch) * distance;
            eye[2] = -.5f + (float)(Math.cos(yaw) * Math.cos(pitch)) * distance;
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0,.1f,-.5f, 0,1,0);
            Matrix.invertM(inverseView, 0, view, 0);
            Matrix.perspectiveM(projection, 0, 32, aspect, .05f, 100);
            if (dirtyOrder || Math.abs(view[2]-lastSortX)+Math.abs(view[6]-lastSortY)+Math.abs(view[10]-lastSortZ) > .0015f) sort();
            int renderWidth = frameWidth(), renderHeight = frameHeight();
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glViewport(0, 0, renderWidth, renderHeight);
            GLES30.glClearColor(0, 0, 0, 1);
            GLES30.glDepthMask(true); GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glDisable(GLES30.GL_BLEND);
            drawMesh(floorMesh);
            for (Mesh mesh : housing) drawMesh(mesh);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            GLES30.glDepthMask(false);
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(-1, -1);
            for (Mesh mesh : decals) drawMesh(mesh);
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
            drawGaussians(now, renderWidth, renderHeight);
            drawGlass();
            GLES30.glDepthMask(true);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, width, height);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND);
            GLES30.glUseProgram(outputProgram);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTexture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "scene"), 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            frameCount++;
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) { lastGlError = error; Log.e(TAG, "GLES error 0x" + Integer.toHexString(error)); }
            needsAnimation = !loaded || now-revealStart<1900 || Math.abs(shortestAngle(targetYaw-yaw))+Math.abs(targetPitch-pitch)+Math.abs(targetRadius-radius)>.001f;
        }

        void reportFailure(String phase, Throwable error) {
            ready = false; readySnapshot = false; needsAnimation = false;
            failure = phase + ": " + error; renderError = failure;
            Log.e(TAG, failure, error);
        }

        void rotate(float dx, float dy) {
            targetYaw -= dx * .008f;
            targetPitch = clamp(targetPitch + dy * .0055f, .055f, 1.2f);
        }
        void zoom(float factor) { if (Float.isFinite(factor) && factor > 0) targetRadius = clamp(targetRadius / factor, 7.2f, 22); }
        void front() { targetYaw = yaw + shortestAngle(-yaw); targetPitch = .071f; targetRadius = 12.2f; }
        void reset() { targetYaw = yaw + shortestAngle(.608f-yaw); targetPitch = .395f; targetRadius = 14.9f; }
        void clear() { loaded = false; memoryLoaded = false; needsAnimation = true; idleData = makeIdle(); data = idleData; if (ready) uploadGaussians(); }

        void load(float[] source) {
            final int n = source.length / 16;
            float[] low = {Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
            float[] high = {-Float.POSITIVE_INFINITY,-Float.POSITIVE_INFINITY,-Float.POSITIVE_INFINITY};
            int focus = 0;
            for (int i=0;i<n;i++) if (source[i*16+10] > .5f && valid(source, i*16)) {
                focus++; for(int a=0;a<3;a++){low[a]=Math.min(low[a],source[i*16+a]);high[a]=Math.max(high[a],source[i*16+a]);}
            }
            if (focus < 100) {
                Arrays.fill(low,Float.POSITIVE_INFINITY);Arrays.fill(high,-Float.POSITIVE_INFINITY);
                for(int i=0;i<n;i++) if(valid(source,i*16))for(int a=0;a<3;a++){low[a]=Math.min(low[a],source[i*16+a]);high[a]=Math.max(high[a],source[i*16+a]);}
            }
            if (!Float.isFinite(low[0])) { Log.w(TAG,"Memory has no valid Gaussian records"); return; }
            float xy=Math.max(3.04f/Math.max(.001f,high[0]-low[0]),2.68f/Math.max(.001f,high[1]-low[1]))*1.07f*.65f;
            float sz=3.22f/Math.max(.001f,high[2]-low[2]);
            float cx=(low[0]+high[0])*.5f,cy=(low[1]+high[1])*.5f,cz=(low[2]+high[2])*.5f;
            float[] transformed=new float[source.length];int written=0;
            for(int i=0;i<n;i++){
                int s=i*16;if(!valid(source,s))continue;
                float x=(source[s]-cx)*xy,y=(source[s+1]-cy)*xy+.84f,z=(source[s+2]-cz)*sz-.97f;
                if(x< -1.54f||x>1.54f||y< -.53f||y>2.21f||z< -2.66f||z>.66f)continue;
                int d=written++*16;transformed[d]=x;transformed[d+1]=y;transformed[d+2]=z;transformed[d+3]=clamp(source[s+3],0,.995f);
                transformed[d+4]=source[s+4]*xy*xy;transformed[d+5]=source[s+5]*xy*xy;transformed[d+6]=source[s+6]*xy*sz;
                transformed[d+7]=source[s+7]*xy*xy;transformed[d+8]=source[s+8]*xy*sz;transformed[d+9]=source[s+9]*sz*sz;
                transformed[d+12]=clamp(source[s+12],0,1);transformed[d+13]=clamp(source[s+13],0,1);transformed[d+14]=clamp(source[s+14],0,1);
            }
            if(written==0){Log.w(TAG,"No Gaussian records remain in chamber");return;}
            data=Arrays.copyOf(transformed,written*16);loaded=true;memoryLoaded=true;needsAnimation=true;revealStart=SystemClock.uptimeMillis();
            if(ready)uploadGaussians();
            Log.i(TAG,"Loaded "+written+" anisotropic Gaussians into chamber");
        }

        static boolean valid(float[] a,int k){for(int j=0;j<16;j++)if(!Float.isFinite(a[k+j]))return false;return a[k+3]>.003f&&a[k+4]>=0&&a[k+7]>=0&&a[k+9]>=0;}

        void uploadGaussians() {
            count=data.length/16;particleCount=count;textureHeight=(count*4+2047)/2048;
            float[] padded=Arrays.copyOf(data,2048*textureHeight*4);
            if(gaussianTexture!=0)GLES30.glDeleteTextures(1,new int[]{gaussianTexture},0);
            int[] id=new int[1];GLES30.glGenTextures(1,id,0);gaussianTexture=id[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,gaussianTexture);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA32F,2048,textureHeight,0,GLES30.GL_RGBA,GLES30.GL_FLOAT,floats(padded));
            order=new float[count];depths=new float[count];buckets=new int[count];orderFloats=floats(new float[count]);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,orderBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,count*4,null,GLES30.GL_DYNAMIC_DRAW);
            dirtyOrder=true;
        }

        /** Stable 16-bit counting sort: no boxed objects or per-frame allocations. */
        void sort(){
            Arrays.fill(histogram,0);float min=Float.POSITIVE_INFINITY,max=-Float.POSITIVE_INFINITY;
            for(int i=0;i<count;i++){int k=i*16;float d=data[k]*view[2]+data[k+1]*view[6]+data[k+2]*view[10];depths[i]=d;min=Math.min(min,d);max=Math.max(max,d);}
            float scale=65535/Math.max(.00001f,max-min);
            for(int i=0;i<count;i++){int b=(int)((depths[i]-min)*scale);b=Math.min(65535,Math.max(0,b));buckets[i]=b;histogram[b]++;}
            int offset=0;for(int i=0;i<65536;i++){int n=histogram[i];histogram[i]=offset;offset+=n;}
            // View-space Z is negative: ascending order draws the farthest first.
            for(int i=0;i<count;i++)order[histogram[buckets[i]]++]=i;
            orderFloats.clear();orderFloats.put(order).flip();
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,orderBuffer);GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,count*4,orderFloats);
            lastSortX=view[2];lastSortY=view[6];lastSortZ=view[10];dirtyOrder=false;
        }

        void drawGaussians(long now,int w,int h){
            GLES30.glUseProgram(gaussianProgram);matrices(gaussianProgram);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(gaussianProgram,"viewport"),w,h);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(gaussianProgram,"time"),(now-began)*.001f);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(gaussianProgram,"reveal"),loaded?Math.min(1,(now-revealStart)/1800f):1);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(gaussianProgram,"idle"),loaded?0:1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,gaussianTexture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(gaussianProgram,"gaussians"),0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,quadBuffer);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,8,0);GLES30.glVertexAttribDivisor(0,0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,orderBuffer);GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(1,1,GLES30.GL_FLOAT,false,4,0);GLES30.glVertexAttribDivisor(1,1);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP,0,4,count);
            GLES30.glVertexAttribDivisor(1,0);GLES30.glDisableVertexAttribArray(1);GLES30.glDisableVertexAttribArray(0);
        }

        void loadHousing()throws Exception{
            JSONArray parts=new JSONArray(assetText("parts.json"));
            for(int i=0;i<parts.length();i++){
                JSONObject part=parts.getJSONObject(i);JSONArray values=part.getJSONArray("v");float[] packed=new float[values.length()];
                for(int j=0;j<packed.length;j++){int component=j%11;float divisor=component<6?10000:component<8?65535:255;packed[j]=(float)values.getDouble(j)/divisor;if(component==7)packed[j]=1-packed[j];}
                Mesh mesh=new Mesh(buffer(packed,GLES30.GL_STATIC_DRAW),packed.length/11);
                String filename=part.optString("texture","");
                if(!filename.isEmpty()&&!filename.equals("null")){mesh.texture=texture(filename);mesh.kind=1;}
                else if(part.optBoolean("baked",false)){mesh.texture=texture("irradiance.png");mesh.kind=2;mesh.intensity=2.88f;}
                housing.add(mesh);
            }
            JSONArray stickers=new JSONArray(assetText("decals.json"));
            for(int i=0;i<stickers.length();i++){
                JSONObject part=stickers.getJSONObject(i);JSONArray values=part.getJSONArray("v");float[] packed=new float[values.length()];
                for(int j=0;j<packed.length;j++)packed[j]=(float)values.getDouble(j);
                for(int j=7;j<packed.length;j+=11)packed[j]=1-packed[j];
                Mesh mesh=new Mesh(buffer(packed,GLES30.GL_STATIC_DRAW),packed.length/11);mesh.texture=texture(part.optBoolean("logo",false)?"logo.png":"rear.jpg");mesh.kind=1;mesh.transparent=true;decals.add(mesh);
            }
        }

        Mesh ground()throws Exception{
            float y=-1.758f;float[] packed={-8,y,-8,0,1,0,0,0,1,1,1, 8,y,-8,0,1,0,1,0,1,1,1, -8,y,8,0,1,0,0,1,1,1,1, -8,y,8,0,1,0,0,1,1,1,1, 8,y,-8,0,1,0,1,0,1,1,1, 8,y,8,0,1,0,1,1,1,1,1};
            Mesh m=new Mesh(buffer(packed,GLES30.GL_STATIC_DRAW),6);m.texture=texture("ground.png");m.kind=2;m.intensity=.26f;m.floor=true;return m;
        }

        void drawMesh(Mesh mesh){
            GLES30.glUseProgram(meshProgram);matrices(meshProgram);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(meshProgram,"kind"),mesh.kind);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(meshProgram,"floorMode"),mesh.floor?1:0);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram,"intensity"),mesh.intensity);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,mesh.texture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(meshProgram,"surface"),0);
            bindMesh(mesh);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,mesh.count);
        }

        Mesh glass(){
            ArrayList<Float> v=new ArrayList<>();
            pane(v,new float[]{-1.60f,-.55f,.638f, 1.60f,-.55f,.638f, 1.60f,2.23f,.638f, -1.60f,2.23f,.638f},new float[]{0,0,1});
            pane(v,new float[]{1.60f,-.55f,-2.667f, -1.60f,-.55f,-2.667f, -1.60f,2.23f,-2.667f, 1.60f,2.23f,-2.667f},new float[]{0,0,-1});
            pane(v,new float[]{1.655f,-.55f,.638f, 1.655f,-.55f,-2.667f, 1.655f,2.23f,-2.667f, 1.655f,2.23f,.638f},new float[]{1,0,0});
            pane(v,new float[]{-1.655f,-.55f,-2.667f, -1.655f,-.55f,.638f, -1.655f,2.23f,.638f, -1.655f,2.23f,-2.667f},new float[]{-1,0,0});
            pane(v,new float[]{-1.60f,2.255f,.638f, 1.60f,2.255f,.638f, 1.60f,2.255f,-2.667f, -1.60f,2.255f,-2.667f},new float[]{0,1,0});
            float[] packed=new float[v.size()];for(int i=0;i<packed.length;i++)packed[i]=v.get(i);return new Mesh(buffer(packed,GLES30.GL_STATIC_DRAW),packed.length/11);
        }
        static void pane(ArrayList<Float> out,float[] p,float[] n){int[] indices={0,1,2,0,2,3};float[] uv={0,0,1,0,1,1,0,1};for(int i:indices){for(int j=0;j<3;j++)out.add(p[i*3+j]);for(float f:n)out.add(f);out.add(uv[i*2]);out.add(uv[i*2+1]);out.add(1f);out.add(1f);out.add(1f);}}

        void drawGlass(){
            GLES30.glUseProgram(glassProgram);matrices(glassProgram);
            GLES30.glUniform3f(GLES30.glGetUniformLocation(glassProgram,"eye"),eye[0],eye[1],eye[2]);
            // Only outward-facing panes contribute; no opaque white overlay.
            GLES30.glEnable(GLES30.GL_CULL_FACE);GLES30.glCullFace(GLES30.GL_BACK);
            bindMesh(glassMesh);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,glassMesh.count);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
        }

        void bindMesh(Mesh mesh){GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,mesh.buffer);int[] sizes={3,3,2,3},offsets={0,12,24,32};for(int i=0;i<4;i++){GLES30.glEnableVertexAttribArray(i);GLES30.glVertexAttribPointer(i,sizes[i],GLES30.GL_FLOAT,false,44,offsets[i]);GLES30.glVertexAttribDivisor(i,0);}}
        void matrices(int p){GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p,"projection"),1,false,projection,0);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p,"view"),1,false,view,0);}
        int frameWidth(){return Math.max(1,Math.round(width*Math.min(1,1400f/Math.max(width,height))));}
        int frameHeight(){return Math.max(1,Math.round(height*Math.min(1,1400f/Math.max(width,height))));}

        void createFramebuffer(){
            if(framebuffer!=0){GLES30.glDeleteFramebuffers(1,new int[]{framebuffer},0);GLES30.glDeleteTextures(1,new int[]{colorTexture},0);GLES30.glDeleteRenderbuffers(1,new int[]{depthBuffer},0);}
            int w=frameWidth(),h=frameHeight();int[] id=new int[1];
            GLES30.glGenFramebuffers(1,id,0);framebuffer=id[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffer);
            GLES30.glGenTextures(1,id,0);colorTexture=id[0];GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,colorTexture);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA16F,w,h,0,GLES30.GL_RGBA,GLES30.GL_HALF_FLOAT,null);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,colorTexture,0);
            GLES30.glGenRenderbuffers(1,id,0);depthBuffer=id[0];GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,depthBuffer);GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT24,w,h);GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,depthBuffer);
            if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE){GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);Log.w(TAG,"Using bounded linear RGBA8 target");}
            if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE){ready=false;readySnapshot=false;failure="Framebuffer incomplete";renderError=failure;Log.e(TAG,failure);}
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        }

        String assetText(String name)throws Exception{try(InputStream in=context.getAssets().open("terminal/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return out.toString("UTF-8");}}
        int texture(String name)throws Exception{
            Integer old=textureCache.get(name);if(old!=null)return old;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPremultiplied=false;
            Bitmap bitmap;try(InputStream in=context.getAssets().open("terminal/"+name)){bitmap=BitmapFactory.decodeStream(in,null,options);}
            if(bitmap==null)throw new IllegalArgumentException("Texture decode failed: "+name);
            int[] id=new int[1];GLES30.glGenTextures(1,id,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id[0]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR_MIPMAP_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,bitmap,0);GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);bitmap.recycle();textureCache.put(name,id[0]);return id[0];
        }
        static FloatBuffer floats(float[] values){FloatBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b;}
        static int buffer(float[] data,int usage){int[] id=new int[1];GLES30.glGenBuffers(1,id,0);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,id[0]);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,data.length*4,floats(data),usage);return id[0];}
        static int shader(int kind,String source){int s=GLES30.glCreateShader(kind);GLES30.glShaderSource(s,source);GLES30.glCompileShader(s);int[] ok=new int[1];GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){String log=GLES30.glGetShaderInfoLog(s);GLES30.glDeleteShader(s);throw new IllegalStateException("GLSL compile: "+log);}return s;}
        static int program(String vertex,String fragment){int v=shader(GLES30.GL_VERTEX_SHADER,vertex),f=shader(GLES30.GL_FRAGMENT_SHADER,fragment),p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);if(ok[0]==0)throw new IllegalStateException("GLSL link: "+GLES30.glGetProgramInfoLog(p));return p;}
        static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
        static float shortestAngle(float value){return(float)Math.atan2(Math.sin(value),Math.cos(value));}

        static float[] makeIdle(){
            Random random=new Random(420);float[] a=new float[6500*16];
            for(int i=0;i<6500;i++){int k=i*16;float x=random.nextFloat()*2-1,z=random.nextFloat()*2-1,h=random.nextFloat();float level=1.10f+.22f*(float)Math.sin(x*3+z*2)+.11f*(float)Math.sin(z*7-x*4);a[k]=x*1.43f;a[k+1]=-.49f+h*level;a[k+2]=-.98f+z*1.52f;a[k+3]=.45f;float s=.013f+random.nextFloat()*.006f;a[k+4]=a[k+7]=a[k+9]=s*s;float top=clamp((h-.72f)*3.8f,0,1);a[k+12]=.10f+.28f*h+.55f*top;a[k+13]=.035f+.05f*h+.72f*top;a[k+14]=.35f+.28f*h+.30f*top;}
            return a;
        }

        static final String MESH_VERTEX = "#version 300 es\nprecision highp float;\nlayout(location=0) in vec3 position;layout(location=1) in vec3 normal;layout(location=2) in vec2 uv;layout(location=3) in vec3 color;uniform mat4 projection,view;out vec2 tex;out vec3 tint;out vec3 world;void main(){tex=uv;tint=color;world=position;gl_Position=projection*view*vec4(position,1.);}";
        static final String MESH_FRAGMENT = "#version 300 es\nprecision highp float;\nin vec2 tex;in vec3 tint;in vec3 world;uniform sampler2D surface;uniform int kind,floorMode;uniform float intensity;out vec4 frag;vec3 linearize(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}void main(){vec4 t=kind==0?vec4(1):texture(surface,tex);if(t.a<.01)discard;vec3 rgb=kind==1?linearize(t.rgb):t.rgb;rgb*=tint*intensity;if(floorMode==1)rgb*=1.-smoothstep(4.5,7.8,length(world.xz));frag=vec4(rgb*t.a,t.a);}";

        static final String GAUSSIAN_VERTEX = "#version 300 es\nprecision highp float;precision highp int;\nlayout(location=0) in vec2 corner;layout(location=1) in float splatIndex;uniform highp sampler2D gaussians;uniform mat4 view,projection;uniform vec2 viewport;uniform float time,reveal;uniform int idle;out vec2 gaussian;out vec4 color;out vec3 support;vec4 fetchData(int i){return texelFetch(gaussians,ivec2(i%2048,i/2048),0);}void main(){int base=int(splatIndex)*4;vec4 p=fetchData(base),a=fetchData(base+1),b=fetchData(base+2);vec3 center=p.xyz;if(idle==1)center.y+=.055*sin(time*.65+center.x*2.+center.z*1.7);vec4 camera=view*vec4(center,1);color=vec4(fetchData(base+3).rgb,p.w);gaussian=corner*3.;support=center;if(camera.z>=-.05){gl_Position=vec4(2,2,2,1);color.a=0.;return;}mat3 covariance=mat3(a.x,a.y,a.z,a.y,a.w,b.x,a.z,b.x,b.y);mat3 rotation=mat3(view);mat3 cv=rotation*covariance*transpose(rotation);vec2 focal=vec2(projection[0][0],projection[1][1])*viewport*.5;float depth=-camera.z;vec3 jx=vec3(focal.x/depth,0,focal.x*camera.x/(depth*depth)),jy=vec3(0,focal.y/depth,focal.y*camera.y/(depth*depth));float xx=dot(jx,cv*jx)+.25,xy=dot(jx,cv*jy),yy=dot(jy,cv*jy)+.25;float middle=(xx+yy)*.5,spread=sqrt(max(0.,(xx-yy)*(xx-yy)*.25+xy*xy));float l1=max(.1,middle+spread),l2=max(.1,middle-spread);vec2 axis=abs(xy)>.00001?normalize(vec2(xy,l1-xx)):(xx>=yy?vec2(1,0):vec2(0,1));vec2 major=axis*min(sqrt(l1),160.),minor=vec2(-axis.y,axis.x)*min(sqrt(l2),160.);vec2 offset=major*gaussian.x+minor*gaussian.y;gl_Position=projection*camera;gl_Position.xy+=offset*2./viewport*gl_Position.w;support=center+transpose(rotation)*vec3(offset*depth/focal,0);if(idle==0){float height=mix(.03,.75,clamp((center.y+.53)/2.74,0.,1.));color.a*=reveal>=1.?1.:smoothstep(height-.005,height+.12,reveal);}}";
        static final String GAUSSIAN_FRAGMENT = "#version 300 es\nprecision highp float;\nin vec2 gaussian;in vec4 color;in vec3 support;out vec4 frag;void main(){if(any(lessThan(support,vec3(-1.54,-.53,-2.66)))||any(greaterThan(support,vec3(1.54,2.21,.66))))discard;float r=dot(gaussian,gaussian);if(r>9.)discard;float alpha=min(.99,color.a*exp(-.5*r));if(alpha<.003)discard;frag=vec4(color.rgb*alpha,alpha);}";

        static final String GLASS_VERTEX = "#version 300 es\nprecision highp float;\nlayout(location=0) in vec3 position;layout(location=1) in vec3 normal;layout(location=2) in vec2 uv;uniform mat4 view,projection;out vec3 world,n;out vec2 tex;void main(){world=position;n=normal;tex=uv;gl_Position=projection*view*vec4(position,1.);}";
        static final String GLASS_FRAGMENT = "#version 300 es\nprecision highp float;\nin vec3 world,n;in vec2 tex;uniform vec3 eye;out vec4 frag;void main(){vec3 V=normalize(eye-world),N=normalize(n);float c=clamp(dot(V,N),0.,1.);float fresnel=.04258+.95742*pow(1.-c,5.);vec3 R=reflect(-V,N),D=normalize(vec3(-.8,.2,1.)),U=normalize(cross(vec3(0,1,0),D)),B=cross(D,U);float facing=dot(R,D);vec2 q=vec2(dot(R,U),dot(R,B))/max(facing,.001);float panel=(1.-smoothstep(.065,.15,abs(q.x)))*(1.-smoothstep(.60,1.1,abs(q.y)))*step(.01,facing)*.5;float edge=min(min(tex.x,1.-tex.x),min(tex.y,1.-tex.y));float rim=1.-smoothstep(.001,.007,edge);float alpha=clamp(fresnel*.10+panel*fresnel*.40+rim*.035,.002,.13);vec3 reflection=vec3(.035,.05,.055)+panel*vec3(.85,.91,.96)+rim*vec3(.12,.20,.19);frag=vec4(reflection*alpha,alpha);}";

        static final String OUTPUT_VERTEX = "#version 300 es\nprecision highp float;\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.-1.,0,1);}";
        static final String OUTPUT_FRAGMENT = "#version 300 es\nprecision highp float;\nin vec2 uv;uniform highp sampler2D scene;out vec4 frag;void main(){vec3 c=max(vec3(0),texture(scene,uv).rgb);c=mix(c*12.92,1.055*pow(c,vec3(1./2.4))-.055,step(vec3(.0031308),c));frag=vec4(c,1);}";
    }
}
