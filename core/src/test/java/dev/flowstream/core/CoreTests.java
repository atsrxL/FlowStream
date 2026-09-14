package dev.flowstream.core;

import java.net.URI;
import java.util.*;

/** Dependency-free executable regression tests. Run through tools/test-core.sh or :core:selfTest. */
public final class CoreTests {
    private static int tests;
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void test(String name,Checked body) throws Exception { body.run();tests++;System.out.println("PASS "+name); }
    private static void ok(boolean b){if(!b)throw new AssertionError();}
    private static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    private static void near(float a,float b){ok(Math.abs(a-b)<.0001f);}
    private static void throwsError(Checked f) throws Exception {try{f.run();}catch(Exception expected){return;}throw new AssertionError("Exception expected");}
    static final class Output implements GestureMachine.Output {
        int taps,switches,delta,starts,ends,scrubStarts,scrubEnds,scrubCommits;float scrubDx,scrubWidth;boolean scrubbing;float forward,reverse;boolean active,everHeld;
        public void tap(){taps++;}
        public void scrubStarted(){scrubStarts++;ok(!scrubbing&&!active);scrubbing=true;}
        public void scrubMoved(float dx,float width){ok(scrubbing);scrubDx=dx;scrubWidth=width;}
        public void scrubEnded(boolean commit){ok(scrubbing);scrubbing=false;scrubEnds++;if(commit)scrubCommits++;}
        public void switchVideo(int d){switches++;delta=d;ok(!active);}
        public void holdStarted(){starts++;ok(!active);active=true;everHeld=true;}
        public void forward(float s){forward=s;ok(active);}
        public void reverse(float s){reverse=s;ok(active);}
        public void holdEnded(){ends++;ok(active);active=false;}
        public void swipePreview(float y,boolean ready){}
    }
    static final class G {
        final Output out=new Output();final GestureMachine m=new GestureMachine(out);
        void down(float x,float y,long t){m.down(x,y,420,900,8,t);}
        void up(float x,float y,long t){m.up(x,y,0,t);}
    }
    private static String response(String href,String props,String status){return "<d:response><d:href>"+href+"</d:href><d:propstat><d:prop>"+props+"</d:prop><d:status>HTTP/1.1 "+status+"</d:status></d:propstat></d:response>";}
    private static String dav(String content){return "<d:multistatus xmlns:d=\"DAV:\">"+content+"</d:multistatus>";}
    public static void main(String[] args)throws Exception{
        test("speed: exact requested menu",()->{float[] expected={.05f,.1f,.15f,.25f,.5f,.75f,.9f,1f,1.1f,1.15f,1.25f,1.35f,1.5f,1.75f,2f,2.5f};eq(SpeedScale.COUNT,expected.length);for(int i=0;i<expected.length;i++)near(SpeedScale.at(i),expected[i]);});
        test("speed: saved speed roundtrips",()->{for(int i=0;i<SpeedScale.COUNT;i++)eq(SpeedScale.nearest(SpeedScale.at(i)),i);near(SpeedScale.normalize(Float.NaN),1f);});
        test("speed: invalid indices rejected",()->{throwsError(()->SpeedScale.at(-1));throwsError(()->SpeedScale.at(SpeedScale.COUNT));});
        test("random: exhaust folder once and reverse history",()->{RandomOrder order=new RandomOrder(new java.util.Random(5));java.util.List<String> paths=java.util.Arrays.asList("a","b","c","d");order.visit("a");String reserved=order.peekNext(paths);ok(reserved.equals(order.next(paths)));String second=order.next(paths),third=order.next(paths);ok(!second.equals(third));ok(order.next(paths)==null);ok(order.previous(paths).equals(second));ok(order.previous(paths).equals(reserved));ok(order.next(paths)==null);});
        test("random: fresh process state and removed files",()->{RandomOrder order=new RandomOrder(new java.util.Random(1));order.visit("a");order.next(java.util.Arrays.asList("a","b"));ok(order.previous(java.util.Arrays.asList("b"))==null);RandomOrder fresh=new RandomOrder();fresh.visit("b");ok("a".equals(fresh.next(java.util.Arrays.asList("a","b"))));});
        test("bottom hold: clamps 0.1x..5x with origin 2x",()->{near(SpeedScale.bottom(0),2f);near(SpeedScale.bottom(-120),1f);near(SpeedScale.bottom(180),5f);near(SpeedScale.bottom(-999),.1f);near(SpeedScale.bottom(999),5f);});
        test("bottom hold: tiny horizontal tremor keeps exactly 2x",()->{for(float x:new float[]{-12,-6,0,6,12})near(SpeedScale.bottom(x),2f);});
        test("middle hold: 24dp dead zone and 0.1x..4x range",()->{near(SpeedScale.reverse(-23.9f),0f);near(SpeedScale.reverse(-24),.1f);near(SpeedScale.reverse(-36),.2f);near(SpeedScale.reverse(-252),2f);near(SpeedScale.reverse(-999),4f);});
        test("tap: short stationary contact only",()->{G g=new G();g.down(210,450,0);g.up(210,450,100);eq(g.out.taps,1);eq(g.out.starts,0);eq(g.out.switches,0);});
        test("tap: jitter within dead zone",()->{G g=new G();g.down(210,450,0);g.m.move(214,453,30);g.up(215,452,120);eq(g.out.taps,1);});
        test("swipe: small vertical motion does not switch or tap",()->{G g=new G();g.down(210,450,0);g.m.move(210,420,100);g.up(210,420,150);eq(g.out.switches,0);eq(g.out.taps,0);});
        test("swipe: upward threshold switches next once",()->{G g=new G();g.down(210,450,0);g.m.move(211,300,120);g.up(211,300,200);eq(g.out.switches,1);eq(g.out.delta,1);});
        test("swipe: downward threshold switches previous",()->{G g=new G();g.down(210,450,0);g.m.move(210,600,100);g.up(210,600,150);eq(g.out.delta,-1);});
        test("swipe: short fast fling accepted above minimum distance",()->{G g=new G();g.down(210,450,0);g.m.move(210,380,50);g.m.up(210,380,-1400,80);eq(g.out.switches,1);});
        test("swipe: 40dp fast flick rejected",()->{G g=new G();g.down(210,450,0);g.m.move(210,410,30);g.m.up(210,410,-5000,40);eq(g.out.switches,0);});
        test("swipe: opposite fling velocity not accepted",()->{G g=new G();g.down(210,450,0);g.m.move(210,380,70);g.m.up(210,380,1400,80);eq(g.out.switches,0);});
        test("swipe: long diagonal rejected",()->{G g=new G();g.down(210,450,0);g.m.move(260,400,50);g.up(340,250,120);eq(g.out.switches,0);});
        test("swipe: axis cannot be stolen after scrub starts",()->{G g=new G();g.down(210,450,0);g.m.move(240,450,50);g.m.move(210,220,100);g.up(210,220,120);eq(g.out.switches,0);eq(g.out.starts,0);});
        test("swipe: return to origin cancels distance commit",()->{G g=new G();g.down(210,450,0);g.m.move(210,200,80);g.m.move(210,447,150);g.up(210,447,200);eq(g.out.switches,0);eq(g.out.taps,0);});
        test("swipe: 450ms cooldown between commits",()->{G g=new G();g.down(210,450,0);g.m.move(210,250,100);g.up(210,250,150);g.down(210,450,200);g.m.move(210,250,250);g.up(210,250,300);eq(g.out.switches,1);g.down(210,450,650);g.m.move(210,250,700);g.up(210,250,750);eq(g.out.switches,2);});
        test("hold: top third starts reverse at 1x",()->{G g=new G();g.down(210,150,0);g.m.holdTimeout(380);near(g.out.reverse,1);g.up(210,150,600);eq(g.out.starts,1);eq(g.out.ends,1);eq(g.out.taps,0);});
        test("hold: middle remains pending without transport",()->{G g=new G();g.down(210,450,0);g.m.holdTimeout(379);eq(g.out.starts,0);g.m.holdTimeout(380);eq(g.out.starts,0);eq(g.m.mode(),GestureMachine.Mode.PENDING);g.up(210,450,500);eq(g.out.ends,0);eq(g.out.taps,0);});
        test("hold: bottom starts 2x then left/right adjust",()->{G g=new G();g.down(210,750,0);g.m.holdTimeout(380);near(g.out.forward,2f);g.m.move(180,750,420);ok(g.out.forward<2&&g.out.forward>.1f);g.m.move(420,750,450);near(g.out.forward,5f);g.up(390,750,500);eq(g.out.ends,1);});
        test("hold: top reverse varies both sides and returns to 1x",()->{G g=new G();g.m.setHoldRangeDp(60);g.down(300,150,0);g.m.holdTimeout(400);near(g.out.reverse,1);g.m.move(240,150,450);near(g.out.reverse,4);g.m.move(360,150,500);near(g.out.reverse,.1f);g.m.move(300,150,550);near(g.out.reverse,1);g.up(300,150,600);eq(g.out.ends,1);});
        test("hold: vertical drift after activation never changes video",()->{G g=new G();g.down(210,750,0);g.m.holdTimeout(400);g.m.move(210,100,500);g.m.up(210,100,-4000,600);eq(g.out.switches,0);eq(g.out.ends,1);});
        test("hold: cannot activate after vertical axis is locked",()->{G g=new G();g.down(210,450,0);g.m.move(210,430,70);g.m.holdTimeout(450);g.up(210,430,600);eq(g.out.starts,0);});
        test("hold: subthreshold diagonal movement still cancels long press eligibility",()->{G g=new G();g.down(210,450,0);g.m.move(222,462,70);g.m.holdTimeout(450);g.up(222,462,600);eq(g.out.starts,0);});
        test("cancel: hold restored once, no tap on later up",()->{G g=new G();g.down(210,750,0);g.m.holdTimeout(400);g.m.cancel();g.m.cancel();g.up(210,750,500);eq(g.out.ends,1);eq(g.out.taps,0);});
        test("cancel: pending long press cannot fire after cancel",()->{G g=new G();g.down(210,450,0);g.m.cancel();g.m.holdTimeout(400);eq(g.out.starts,0);});
        test("edges: Android back and home strips reserved",()->{for(float[] p:new float[][]{{10,450},{415,450},{210,5},{210,890}}){G g=new G();g.down(p[0],p[1],0);g.m.move(p[0],p[1]-250,100);g.m.holdTimeout(400);g.up(p[0],p[1]-250,500);eq(g.out.taps,0);eq(g.out.switches,0);eq(g.out.starts,0);}});
        test("zones: starting zone remains latched across boundaries",()->{G g=new G();g.down(210,750,0);g.m.holdTimeout(400);g.m.move(0,450,500);near(g.out.forward,.1f);eq(g.out.reverse,0f);g.m.cancel();});
        test("clock: reverse 0.1x advances by 100ms in 1s",()->{ReverseClock c=new ReverseClock();c.start(10000,0,.1f);for(int t=100;t<=1000;t+=100)c.target(t);eq(c.target(1000),9900L);});
        test("clock: independent of keyframe-rounded player position",()->{ReverseClock c=new ReverseClock();c.start(2000,0,2f);eq(c.target(150),1700L);eq(c.target(300),1400L);});
        test("clock: clamps at file start",()->{ReverseClock c=new ReverseClock();c.start(150,0,2f);eq(c.target(500),0L);eq(c.target(1000),0L);});
        test("clock: suspended UI does not cause huge jump",()->{ReverseClock c=new ReverseClock();c.start(100000,0,2f);eq(c.target(10000),99000L);});
        test("paths: preserve Unicode, plus, percent, spaces",()->{eq(MediaPaths.join("电影","A+B 100%.mp4"),"电影/A+B 100%.mp4");eq(MediaPaths.parent("电影/片.mp4"),"电影");eq(MediaPaths.name("电影/片.mp4"),"片.mp4");});
        test("paths: traversal and control characters rejected",()->{for(String s:new String[]{"../x","x/../y","x/./y","x//y","x/\u0000y"})throwsError(()->MediaPaths.normalize(s));});
        test("paths: only video and playlist candidates",()->{ok(MediaPaths.video("a.MKV"));ok(MediaPaths.video("a.m3u8"));ok(MediaPaths.video("a.mpd"));ok(!MediaPaths.video("a.jpg"));ok(!MediaPaths.video("a.mp4.exe"));});
        test("queue: boundaries do not wrap",()->{eq(MediaPaths.adjacent(0,-1,3),-1);eq(MediaPaths.adjacent(2,1,3),-1);eq(MediaPaths.adjacent(1,1,3),2);eq(MediaPaths.adjacent(0,2,3),-1);});
        test("history: complete video resumes from beginning",()->{eq(MediaPaths.resumePosition(9900,10000),0L);eq(MediaPaths.resumePosition(3000,10000),3000L);eq(MediaPaths.resumePosition(-1,0),0L);});
        test("DAV: URL encoding exactly once and + preserved",()->{DavUrls u=new DavUrls("https://nas.example/dav/",false);String p="电影/A+B 100%.mp4";URI target=u.uri(p,false);ok(target.toASCIIString().contains("%2B"));eq(u.relative(u.uri("",true),target.toASCIIString()),p);eq(u.relative(u.uri("",true),"/dav/A+B.mp4"),"A+B.mp4");});
        test("DAV: HTTP opt-in required",()->{throwsError(()->new DavUrls("http://nas/dav/",false));eq(new DavUrls("http://nas/dav",true).root(),"http://nas/dav/");});
        test("DAV: embedded credentials, queries and fragments rejected",()->{throwsError(()->new DavUrls("https://user:pass@nas/dav/",false));throwsError(()->new DavUrls("https://nas/dav/?token=x",false));throwsError(()->new DavUrls("https://nas/dav/#x",false));});
        test("DAV: root traversal rejected",()->{throwsError(()->new DavUrls("https://nas/dav/../secret/",false));throwsError(()->new DavUrls("https://nas/dav/%2e%2e/secret/",false));});
        test("DAV: same-origin credential isolation",()->{DavUrls u=new DavUrls("https://nas/dav/",false);ok(u.allows(URI.create("https://nas:443/dav/a.mp4")));ok(!u.allows(URI.create("https://evil/dav/a.mp4")));ok(!u.allows(URI.create("http://nas/dav/a.mp4")));ok(!u.allows(URI.create("https://nas:444/dav/a.mp4")));});
        test("DAV: root prefix boundary enforced",()->{DavUrls u=new DavUrls("https://nas/dav/",false);ok(!u.allows(URI.create("https://nas/dav-evil/a.mp4")));ok(!u.allows(URI.create("https://nas/secret/a.mp4")));ok(!u.allows(URI.create("https://nas/dav/%2e%2e/secret/a.mp4")));});
        test("DAV: encoded separators rejected",()->{DavUrls u=new DavUrls("https://nas/dav/",false);ok(!u.allows(URI.create("https://nas/dav/a%2fb")));ok(!u.allows(URI.create("https://nas/dav/a%5cb")));});
        test("DAV: relative href directory resolution",()->{DavUrls u=new DavUrls("https://nas/dav/",false);eq(u.relative(u.uri("folder",true),"clip.mp4"),"folder/clip.mp4");});
        test("DAV: successful propstat, directory, 64-bit size",()->{
            DavUrls u=new DavUrls("https://nas/dav/",false);String xml=dav(response("/dav/","<d:resourcetype><d:collection/></d:resourcetype>","200 OK")+response("/dav/folder/","<d:resourcetype><d:collection/></d:resourcetype>","200 OK")+response("/dav/a.mp4","<d:displayname>A &amp; B</d:displayname><d:getcontentlength>5000000000</d:getcontentlength>","200 OK"));
            List<RemoteItem> items=DavParser.parse(xml,u,"");eq(items.size(),2);ok(items.get(0).directory);eq(items.get(1).size,5000000000L);eq(items.get(1).name,"A & B");
        });
        test("DAV: 404 props, self, recursive and foreign href ignored",()->{
            DavUrls u=new DavUrls("https://nas/dav/",false);String xml=dav(response("/dav/","","200 OK")+response("/dav/a.mp4","","404 Not Found")+response("https://evil/dav/b.mp4","","200 OK")+response("/dav/nested/c.mp4","","200 OK"));eq(DavParser.parse(xml,u,"").size(),0);
        });
        test("DAV: duplicate paths deduplicated",()->{DavUrls u=new DavUrls("https://nas/dav/",false);String r=response("/dav/a.mp4","","200 OK");eq(DavParser.parse(dav(r+r),u,"").size(),1);});
        test("DAV: XML DTD and external entities rejected",()->{DavUrls u=new DavUrls("https://nas/dav/",false);throwsError(()->DavParser.parse("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]>"+dav(""),u,""));});
        test("DAV: unexpected HTML and malformed XML rejected",()->{DavUrls u=new DavUrls("https://nas/dav/",false);throwsError(()->DavParser.parse("<html>login</html>",u,""));throwsError(()->DavParser.parse("<d:multistatus>",u,""));});
        test("DAV: non-DAV namespace rejected",()->{DavUrls u=new DavUrls("https://nas/dav/",false);throwsError(()->DavParser.parse("<multistatus xmlns=\"urn:evil\"/>",u,""));});
        test("DAV: bounded response protects parser",()->{DavUrls u=new DavUrls("https://nas/dav/",false);throwsError(()->DavParser.parse("x".repeat(DavParser.MAX_XML_BYTES+1),u,""));});
        test("fuzz: 20,000 gesture streams preserve exclusivity and release balance",()->{
            Random r=new Random(0xF10C45L);
            for(int trial=0;trial<20000;trial++){
                G g=new G();long t=0;float x=30+r.nextFloat()*360,y=40+r.nextFloat()*800;g.down(x,y,t);
                for(int j=0;j<12;j++){t+=r.nextInt(100);x+=(r.nextFloat()-.5f)*65;y+=(r.nextFloat()-.5f)*80;if(r.nextInt(10)==0)g.m.holdTimeout(t);g.m.move(x,y,t);if(r.nextInt(80)==0)g.m.cancel();}
                g.m.up(x,y,(r.nextFloat()-.5f)*4000,t+30);g.m.cancel();
                ok(g.out.switches<=1);ok(g.out.taps<=1);ok(!(g.out.taps>0 && g.out.switches>0));eq(g.out.starts,g.out.ends);eq(g.out.scrubStarts,g.out.scrubEnds);ok(!g.out.scrubbing);ok(!(g.out.scrubStarts>0 && (g.out.starts>0 || g.out.switches>0 || g.out.taps>0)));ok(!g.out.active);if(g.out.everHeld)eq(g.out.switches,0);
            }
        });
        test("hold: one-centimetre endpoints do not depend on origin",()->{float range=160f/2.54f;for(int start=70;start<=350;start+=3){for(int zone:new int[]{0,2}){G g=new G();float y=zone==0?150:750;g.down(start,y,0);g.m.holdTimeout(380);g.m.move(start-range,y,400);near(zone==0?g.out.reverse:g.out.forward,zone==0?4:.1f);g.m.move(start+range,y,420);near(zone==0?g.out.reverse:g.out.forward,zone==0?.1f:5);g.m.move(start,y,440);near(zone==0?g.out.reverse:g.out.forward,zone==0?1:2);g.m.cancel();}}});
        test("hold: custom physical range changes sensitivity",()->{G g=new G();g.m.setHoldRangeDp(100);g.down(210,750,0);g.m.holdTimeout(380);g.m.move(270,750,400);ok(g.out.forward<5);g.m.move(310,750,420);near(g.out.forward,5);g.m.move(110,750,440);near(g.out.forward,.1f);g.m.cancel();});
        test("scrub: middle drag begins immediately and commits final position",()->{G g=new G();g.down(210,450,0);g.m.move(225,451,50);eq(g.out.scrubStarts,1);near(g.out.scrubDx,15);near(g.out.scrubWidth,420);g.up(260,451,80);eq(g.out.scrubEnds,1);eq(g.out.scrubCommits,1);near(g.out.scrubDx,50);eq(g.out.taps,0);eq(g.out.starts,0);eq(g.out.switches,0);});
        test("scrub: stationary wait does not prevent later progress drag",()->{G g=new G();g.down(210,450,0);g.m.holdTimeout(1000);g.m.move(180,450,1100);eq(g.out.scrubStarts,1);g.up(170,450,1200);eq(g.out.scrubCommits,1);eq(g.out.starts,0);});
        test("scrub: cancellation restores once without committing",()->{G g=new G();g.down(210,450,0);g.m.move(180,450,50);g.m.cancel();g.m.cancel();g.up(170,450,200);eq(g.out.scrubEnds,1);eq(g.out.scrubCommits,0);eq(g.out.taps,0);});
        test("swipe: all three start zones retain vertical navigation",()->{for(int y:new int[]{180,450,740}){G g=new G();g.down(210,y,0);g.m.move(210,y-150,100);g.up(210,y-150,150);eq(g.out.switches,1);eq(g.out.delta,1);eq(g.out.scrubStarts,0);eq(g.out.starts,0);}});
        test("multi-tap: delayed and distant singles keep their own coordinates",()->{
            java.util.List<String> points=new java.util.ArrayList<>();MultiTapResolver r=new MultiTapResolver(300,60,new MultiTapResolver.Output(){public void singleTap(){}public void singleTapAt(float x,float y){points.add(x+","+y);}public void doubleTap(){}public void tripleTap(){}});
            r.down(100,200,0);r.up(true,100,200,50);r.down(500,800,100);r.up(true,500,800,150);r.confirm(450);
            if(!points.equals(java.util.Arrays.asList("100.0,200.0","500.0,800.0")))throw new AssertionError(points);
        });
        test("multi-tap: single double triple exclusive and cancelled",()->{
            int[] calls={0,0,0};MultiTapResolver r=new MultiTapResolver(300,60,new MultiTapResolver.Output(){public void singleTap(){calls[0]++;}public void doubleTap(){calls[1]++;}public void tripleTap(){calls[2]++;}});
            r.down(100,100,0);r.up(true,100,100,20);r.confirm(319);eq(calls[0],0);r.confirm(320);eq(calls[0],1);
            r.down(100,100,1000);r.up(true,100,100,1020);r.down(100,100,1080);r.up(true,100,100,1100);eq(calls[1],0);r.confirm(1400);eq(calls[1],1);
            for(int i=0;i<3;i++){r.down(100,100,2000+i*80);r.up(true,100,100,2020+i*80);}eq(calls[2],1);eq(calls[0],1);eq(calls[1],1);
            r.down(100,100,3000);r.up(true,100,100,3020);r.down(100,100,3080);r.up(true,100,100,3100);r.down(100,100,3160);r.up(false,160,100,3200);r.confirm(4000);eq(calls[1],1);
            r.down(100,100,5000);r.up(true,100,100,5020);r.cancel();r.confirm(5400);eq(calls[0],1);
        });
        test("viewport: three presets and persistent custom",()->{VideoViewport v=new VideoViewport();v.setViewport(1080,2340);v.setVideoSize(640,360,1);v.preset(VideoViewport.Mode.WIDTH);near((float)v.width(),1080);v.preset(VideoViewport.Mode.HEIGHT);near((float)v.height(),2340);v.preset(VideoViewport.Mode.NATIVE);near((float)v.width(),640);v.pinch(1,540,1170,540,1170);v.finishTransform();ok(v.mode()==VideoViewport.Mode.CUSTOM);});
        tests += InteractionTests.run();
        System.out.println("\nALL "+tests+" TEST GROUPS PASSED (30,000 gesture streams + 10,000 geometry streams)");
    }
}
