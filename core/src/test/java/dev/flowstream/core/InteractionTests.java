package dev.flowstream.core;

import java.util.Objects;
import java.util.Random;

/** Executable geometry/timing regressions; these are NOT Android MotionEvent/device tests. */
public final class InteractionTests {
    private static int groups;
    @FunctionalInterface interface Test { void run(); }
    private static void test(String name, Test body) { body.run(); groups++; System.out.println("PASS "+name); }
    private static void ok(boolean value) { if (!value) throw new AssertionError(); }
    private static void eq(Object a,Object b) { if (!Objects.equals(a,b)) throw new AssertionError(a+" != "+b); }
    private static void near(double a,double b) { if (Math.abs(a-b)>1e-6) throw new AssertionError(a+" != "+b); }
    private static VideoViewport viewport(int w,int h,int vw,int vh) {
        VideoViewport v=new VideoViewport();v.setViewport(vw,vh);v.setVideoSize(w,h,1);return v;
    }
    private static final class TapOut implements TapResolver.Output {
        int single,doubled;float x,y;
        public void singleTap(){single++;}
        public void doubleTap(float x,float y){doubled++;this.x=x;this.y=y;}
    }
    private static final class T {
        final TapOut out=new TapOut();final TapResolver taps=new TapResolver(300,80,out);
        void contact(float x,float y,long down,long up){taps.down(x,y,down);taps.up(true,x,y,up);}
    }
    private static final class GestureOut implements GestureMachine.Output {
        int taps,switches,starts,ends,pans;float dx,dy,speed;boolean held;
        public void tap(){taps++;}
        public void switchVideo(int delta){ok(!held);switches++;}
        public void holdStarted(){ok(!held);held=true;starts++;}
        public void holdEnded(){ok(held);held=false;ends++;}
        public void forward(float speed){ok(held);this.speed=speed;}
        public void reverse(float magnitude){ok(held);}
        public void swipePreview(float dy,boolean enough){}
        public void pan(float x,float y){ok(!held);pans++;dx+=x;dy+=y;}
    }
    private static void bounds(VideoViewport v) {
        ok(Double.isFinite(v.left()) && Double.isFinite(v.top()) && Double.isFinite(v.scale()));
        ok(v.scale()>=v.minScale()-1e-8 && v.scale()<=v.maxScale()+1e-8);
        if(v.width()>v.viewportWidth()) {ok(v.left()<=1e-6);ok(v.left()+v.width()>=v.viewportWidth()-1e-6);}
        else ok(Math.abs(v.left()-(v.viewportWidth()-v.width())/2)<=.501);
        if(v.height()>v.viewportHeight()) {ok(v.top()<=1e-6);ok(v.top()+v.height()>=v.viewportHeight()-1e-6);}
        else ok(Math.abs(v.top()-(v.viewportHeight()-v.height())/2)<=.501);
    }
    public static int run() {
        groups=0;
        test("tap resolver: a single tap waits for double-tap timeout",()->{T t=new T();t.contact(200,400,0,70);t.taps.confirm(369);eq(t.out.single,0);t.taps.confirm(370);eq(t.out.single,1);t.taps.confirm(999);eq(t.out.single,1);});
        test("tap resolver: valid double tap never pauses once or twice",()->{T t=new T();t.contact(200,400,0,70);t.contact(205,403,200,260);t.taps.confirm(1000);eq(t.out.single,0);eq(t.out.doubled,1);near(t.out.x,205);});
        test("tap resolver: second DOWN does not commit zoom",()->{T t=new T();t.contact(200,400,0,70);t.taps.down(200,400,200);eq(t.out.doubled,0);t.taps.up(true,200,400,260);eq(t.out.doubled,1);});
        test("tap resolver: cancellation removes deferred pause",()->{T t=new T();t.contact(200,400,0,70);t.taps.cancel();t.taps.confirm(1000);eq(t.out.single,0);eq(t.taps.deadline(),-1L);});
        test("tap resolver: second contact drag never zooms or pauses",()->{T t=new T();t.contact(200,400,0,70);t.taps.down(200,400,200);t.taps.up(false,200,250,290);t.taps.confirm(1000);eq(t.out.single,0);eq(t.out.doubled,0);});
        test("tap resolver: second contact hold never zooms or pauses",()->{T t=new T();t.contact(200,400,0,70);t.taps.down(200,400,200);t.taps.confirm(400);t.taps.up(true,200,400,700);t.taps.confirm(1000);eq(t.out.single,0);eq(t.out.doubled,0);});
        test("tap resolver: pending confirmation freezes during second contact",()->{T t=new T();t.contact(200,400,0,70);t.taps.down(200,400,350);eq(t.taps.deadline(),-1L);t.taps.confirm(400);eq(t.out.single,0);t.taps.up(true,200,400,450);eq(t.out.doubled,1);});
        test("tap resolver: separated taps are two singles",()->{T t=new T();t.contact(200,400,0,70);t.contact(200,400,500,550);eq(t.out.single,1);t.taps.confirm(850);eq(t.out.single,2);eq(t.out.doubled,0);});
        test("tap resolver: distant taps are not double tap",()->{T t=new T();t.contact(200,400,0,70);t.contact(350,400,200,260);t.taps.confirm(1000);eq(t.out.single,2);eq(t.out.doubled,0);});
        test("tap resolver: triple tap is a double then one single",()->{T t=new T();t.contact(200,400,0,60);t.contact(200,400,150,210);t.contact(200,400,300,350);t.taps.confirm(700);eq(t.out.single,1);eq(t.out.doubled,1);});
        test("tap resolver: exact time and distance boundary accepted",()->{T t=new T();t.contact(200,400,0,70);t.contact(280,400,370,400);eq(t.out.doubled,1);eq(t.out.single,0);});
        test("tap resolver: spurious UP after CANCEL has no effect",()->{T t=new T();t.taps.down(200,400,0);t.taps.cancel();t.taps.up(true,200,400,50);t.taps.confirm(1000);eq(t.out.single,0);});
        test("tap resolver: a callback can cancel reentrantly",()->{TapResolver[] ref=new TapResolver[1];int[] counts={0,0};ref[0]=new TapResolver(300,80,new TapResolver.Output(){public void singleTap(){counts[0]++;ref[0].cancel();}public void doubleTap(float x,float y){counts[1]++;ref[0].cancel();}});ref[0].down(200,400,0);ref[0].up(true,200,400,50);ref[0].confirm(350);ref[0].confirm(400);eq(counts[0],1);eq(counts[1],0);});
        test("viewport: 4K FIT does not crop",()->{VideoViewport v=viewport(3840,2160,1080,2400);near(v.scale(),.28125);near(v.width(),1080);near(v.height(),607.5);near(v.left(),0);near(v.top(),896.25);ok(!v.inspectMode());});
        test("viewport: 4K double tap is exactly native, not fixed 2x",()->{VideoViewport v=viewport(3840,2160,1080,2400);ok(v.toggleNative(540,1200));near(v.scale(),1);near(v.width(),3840);near(v.height(),2160);near(v.left(),-1380);near(v.top(),120);ok(v.inspectMode());});
        test("viewport: double tap again restores FIT and recenters",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.toggleNative(400,1100);v.pan(500,500);v.toggleNative(200,300);eq(v.mode(),VideoViewport.Mode.FIT);near(v.left(),0);near(v.top(),896.25);});
        test("viewport: native low-res video shrinks rather than fake enlarging",()->{VideoViewport v=viewport(640,360,1080,2400);near(v.scale(),1.6875);v.toggleNative(540,1200);near(v.scale(),1);near(v.width(),640);near(v.height(),360);near(v.left(),220);near(v.top(),1020);});
        test("viewport: non-square pixels keep display aspect and native line height",()->{VideoViewport v=new VideoViewport();v.setViewport(1080,2400);v.setVideoSize(720,576,16.0/15);v.toggleNative(540,1200);near(v.width(),768);near(v.height(),576);near(v.scale(),1);});
        test("viewport: already-rotated portrait dimensions are not rotated twice",()->{VideoViewport v=viewport(1080,1920,1080,2400);v.toggleNative(540,1200);near(v.width(),1080);near(v.height(),1920);near(v.top(),240);});
        test("viewport: double tap keeps focal video sample under tap",()->{VideoViewport v=viewport(2160,3840,1080,1920);double sourceX=(250-v.left())/v.scale(),sourceY=(500-v.top())/v.scale();v.toggleNative(250,500);near((250-v.left())/v.scale(),sourceX);near((500-v.top())/v.scale(),sourceY);});
        test("viewport: native square pixels align to integer origins",()->{VideoViewport v=viewport(1920,1080,1081,2401);v.toggleNative(520.35,1200.15);v.pan(11.3,-4.4);near(v.left(),Math.rint(v.left()));near(v.top(),Math.rint(v.top()));bounds(v);});
        test("viewport: native pan clamps both ends without blank overscroll",()->{VideoViewport v=viewport(3840,2160,1080,1920);v.toggleNative(540,960);v.pan(1e8,1e8);near(v.left(),0);near(v.top(),0);v.pan(-2e8,-2e8);near(v.left(),1080-3840);near(v.top(),1920-2160);bounds(v);});
        test("viewport: axis smaller than screen stays centered",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.toggleNative(540,1200);v.pan(400,800);near(v.top(),120);near(v.left(),-980);});
        test("viewport: FIT drag is ignored",()->{VideoViewport v=viewport(3840,2160,1080,2400);double l=v.left(),t=v.top();v.pan(100,100);near(v.left(),l);near(v.top(),t);});
        test("viewport: pinch anchors and translates around moving centroid",()->{VideoViewport v=viewport(2160,3840,1080,1920);double px=(500-v.left())/v.scale(),py=(900-v.top())/v.scale();v.pinch(2,500,900,530,910);near((530-v.left())/v.scale(),px);near((910-v.top())/v.scale(),py);eq(v.mode(),VideoViewport.Mode.CUSTOM);});
        test("viewport: extreme pinch is bounded",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.pinch(1e99,540,1200,540,1200);near(v.scale(),v.maxScale());bounds(v);v.pinch(1e-99,540,1200,540,1200);near(v.scale(),v.minScale());bounds(v);});
        test("viewport: zoom ceiling never prevents native size",()->{VideoViewport v=viewport(16384,16384,500,900);ok(v.maxScale()>=1);v.toggleNative(250,450);near(v.scale(),1);});
        test("viewport: custom zoom double tap returns to FIT",()->{VideoViewport v=viewport(2160,3840,1080,1920);v.pinch(3,500,900,500,900);v.toggleNative(500,900);eq(v.mode(),VideoViewport.Mode.FIT);near(v.scale(),.5);});
        test("viewport: near-FIT snap occurs only after gesture ends",()->{VideoViewport v=viewport(2160,3840,1080,1920);v.pinch(1.01,500,900,500,900);eq(v.mode(),VideoViewport.Mode.CUSTOM);v.finishTransform();eq(v.mode(),VideoViewport.Mode.FIT);near(v.scale(),.5);});
        test("viewport: rotation clears old offsets",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.toggleNative(500,1200);v.pan(300,0);v.setViewport(2400,1080);eq(v.mode(),VideoViewport.Mode.FIT);near(v.width(),1920);near(v.left(),240);near(v.height(),1080);});
        test("viewport: repeated same layout does not reset native",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.toggleNative(500,1200);v.setViewport(1080,2400);eq(v.mode(),VideoViewport.Mode.NATIVE);});
        test("viewport: repeated video size preserves zoom for single-item loop",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.toggleNative(500,1200);v.setVideoSize(3840,2160,1);eq(v.mode(),VideoViewport.Mode.NATIVE);});
        test("viewport: next video of SAME size still starts FIT",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.toggleNative(500,1200);v.clearVideo();v.setVideoSize(3840,2160,1);eq(v.mode(),VideoViewport.Mode.FIT);});
        test("viewport: unknown size rejects native without NaN",()->{VideoViewport v=new VideoViewport();v.setViewport(1080,2400);ok(!v.toggleNative(500,1200));v.pinch(2,1,1,1,1);ok(Double.isFinite(v.scale()));});
        test("viewport: resolution change resets zoom",()->{VideoViewport v=viewport(3840,2160,1080,2400);v.toggleNative(500,1200);v.setVideoSize(1920,1080,1);eq(v.mode(),VideoViewport.Mode.FIT);near(v.width(),1080);});
        test("viewport: malformed transform inputs are ignored",()->{VideoViewport v=viewport(1920,1080,1080,2400);double s=v.scale(),l=v.left();v.pinch(Double.NaN,0,0,0,0);v.pinch(-1,0,0,0,0);v.pinch(1,0,0,Double.POSITIVE_INFINITY,0);v.pan(Double.NaN,0);near(v.scale(),s);near(v.left(),l);ok(!v.toggleNative(Double.NaN,0));});
        test("viewport: zero layout and unknown SAR safely handled",()->{VideoViewport v=viewport(1920,1080,1080,2400);v.setViewport(0,0);ok(v.ready());v.setVideoSize(1920,1080,Double.NaN);near(v.pixelRatio(),1);v.setVideoSize(0,0,1);ok(!v.ready());});
        test("inspection gesture: vertical drag pans without switching",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,450,420,900,8,0,true);g.move(200,200,100);g.up(200,150,-4000,150);eq(o.switches,0);eq(o.taps,0);near(o.dy,-300);eq(g.mode(),GestureMachine.Mode.IDLE);});
        test("inspection gesture: diagonal and horizontal drags pan",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,450,420,900,8,0,true);g.move(250,500,100);g.move(290,500,130);g.up(310,510,0,160);near(o.dx,110);near(o.dy,60);eq(o.switches,0);eq(o.taps,0);});
        test("inspection gesture: tiny jitter remains a tap candidate",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,450,420,900,8,0,true);g.move(205,453,50);g.up(205,453,0,80);eq(o.taps,1);eq(o.pans,0);});
        test("inspection gesture: stationary middle hold still plays at 1x",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,450,420,900,8,0,true);g.holdTimeout(380);g.move(200,200,450);g.up(200,200,-4000,500);near(o.speed,1);eq(o.starts,1);eq(o.ends,1);eq(o.pans,0);eq(o.switches,0);});
        test("inspection gesture: bottom hold keeps speed control, not pan",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,750,420,900,8,0,true);g.holdTimeout(380);g.move(380,700,450);near(o.speed,5);g.cancel();eq(o.ends,1);eq(o.pans,0);});
        test("inspection gesture: pan cannot become hold after timeout",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,450,420,900,8,0,true);g.move(230,450,80);g.holdTimeout(600);g.up(230,450,0,650);eq(o.starts,0);eq(o.switches,0);eq(o.taps,0);});
        test("inspection gesture: cancellation cannot produce switch or tap",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,450,420,900,8,0,true);g.move(200,100,100);g.cancel();g.up(200,100,-5000,200);eq(o.switches,0);eq(o.taps,0);});
        test("inspection gesture: after reset FIT vertical switching works",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(200,450,420,900,8,0,true);g.move(200,200,100);g.up(200,200,0,150);g.down(200,450,420,900,8,700,false);g.move(200,200,800);g.up(200,200,0,850);eq(o.switches,1);});
        test("inspection gesture: native still respects system edges",()->{GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);g.down(10,450,420,900,8,0,true);g.move(200,200,100);g.up(200,200,0,150);eq(o.pans,0);eq(o.switches,0);eq(o.taps,0);});
        test("fuzz: 10,000 viewports x 25 operations preserve crop/scale bounds",()->{Random r=new Random(0x200F10L);for(int i=0;i<10000;i++){VideoViewport v=viewport(100+r.nextInt(8000),100+r.nextInt(8000),300+r.nextInt(1600),300+r.nextInt(2500));for(int n=0;n<25;n++){double x=r.nextDouble()*v.viewportWidth(),y=r.nextDouble()*v.viewportHeight();switch(r.nextInt(5)){case 0->v.toggleNative(x,y);case 1->v.pinch(Math.exp(r.nextDouble()*4-2),x,y,x+r.nextDouble()*50-25,y+r.nextDouble()*50-25);case 2->v.pan(r.nextDouble()*10000-5000,r.nextDouble()*10000-5000);case 3->v.finishTransform();case 4->v.resetFit();}bounds(v);}}});
        test("fuzz: 10,000 inspection streams never switch and balance holds",()->{Random r=new Random(0xD0B1EL);for(int i=0;i<10000;i++){GestureOut o=new GestureOut();GestureMachine g=new GestureMachine(o);float x=30+r.nextFloat()*360,y=40+r.nextFloat()*800;long t=0;g.down(x,y,420,900,8,t,true);for(int n=0;n<15;n++){t+=r.nextInt(100);x+=r.nextFloat()*80-40;y+=r.nextFloat()*80-40;if(r.nextInt(5)==0)g.holdTimeout(t);g.move(x,y,t);}g.up(x,y,r.nextFloat()*8000-4000,t+50);g.cancel();eq(o.switches,0);eq(o.starts,o.ends);ok(!(o.pans>0 && o.starts>0));ok(!(o.pans>0 && o.taps>0));}});
        return groups;
    }
}
