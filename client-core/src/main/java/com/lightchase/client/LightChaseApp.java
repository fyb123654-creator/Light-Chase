package com.lightchase.client;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import java.util.concurrent.ConcurrentLinkedQueue;

/** S0 client: clearly marked offline fixture, multi-touch input and role-filtered rendering. */
public final class LightChaseApp extends ApplicationAdapter {
    private final ClientGateway gateway;
    private final ConcurrentLinkedQueue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private final OrthographicCamera worldCamera=new OrthographicCamera();
    private final OrthographicCamera uiCamera=new OrthographicCamera();
    private final FitViewport world=new FitViewport(128f/9f,8,worldCamera);
    private final FitViewport ui=new FitViewport(1280,720,uiCamera);
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
    private DataFiles data;
    private PlayerView view;
    private Role selectedRole=Role.THIEF;
    private boolean running,interact,dropPulse;
    private String connection="OFFLINE",notice="Choose role, then START DEMO";
    private int joystickPointer=-1,interactPointer=-1;
    private double moveX,moveY;
    private static final float JOY_X=145,JOY_Y=130,JOY_R=85;
    public LightChaseApp(ClientGateway gateway) { this.gateway=gateway; }

    @Override public void create() {
        data=DataFiles.read(Gdx.files.internal("maps/test-room.json").readBytes(),Gdx.files.internal("rules/v1.json").readBytes());
        if(gateway instanceof FakeGateway) ((FakeGateway)gateway).configure(data);
        shapes=new ShapeRenderer();batch=new SpriteBatch();font=new BitmapFont();font.getData().setScale(1.45f);
        Gdx.input.setInputProcessor(new InputAdapter() {
            private Vector2 point(int sx,int sy) { return ui.unproject(new Vector2(sx,sy)); }
            @Override public boolean touchDown(int sx,int sy,int pointer,int button) {
                Vector2 p=point(sx,sy);
                if(p.x<0||p.x>1280||p.y<0||p.y>720)return false;
                if(p.y>592 && p.y<644) {
                    if(p.x>30&&p.x<235) { if(!running){selectedRole=Role.POLICE;gateway.selectRole(selectedRole);}return true; }
                    if(p.x>250&&p.x<455) { if(!running){selectedRole=Role.THIEF;gateway.selectRole(selectedRole);}return true; }
                    if(p.x>485&&p.x<735) { clearInput();if(running)gateway.returnToLobby();else gateway.setReady(true);return true; }
                }
                if(!running)return false;
                if(joystickPointer==-1 && p.dst(JOY_X,JOY_Y)<=JOY_R*1.35f) {joystickPointer=pointer;joystick(p);return true;}
                if(selectedRole==Role.THIEF&&p.dst(1125,145)<=70) {interactPointer=pointer;interact=true;return true;}
                if(selectedRole==Role.THIEF&&p.dst(965,105)<=50) {dropPulse=true;notice="DROP request captured (fixture only)";return true;}
                return false;
            }
            @Override public boolean touchDragged(int sx,int sy,int pointer) {
                Vector2 p=point(sx,sy);
                if(pointer==joystickPointer){joystick(p);return true;}
                if(pointer==interactPointer){interact=p.dst(1125,145)<=70;return true;}return false;
            }
            @Override public boolean touchUp(int sx,int sy,int pointer,int button) {release(pointer);return true;}
            @Override public boolean touchCancelled(int sx,int sy,int pointer,int button) {release(pointer);return true;}
            @Override public boolean keyDown(int key) {
                if(key==Input.Keys.ESCAPE){clearInput();gateway.returnToLobby();return true;}return false;
            }
        });
        connectFixture();
    }
    private void connectFixture() {
        gateway.connect(new ConnectionOptions("offline",8080,"DEMO","s0",data.mapHash,data.configHash),new GatewayListener(){
            public void onConnection(ConnectionStatus s){callbacks.add(()->{connection=s.state;if("CLOSED".equals(s.state)){running=false;view=null;clearInput();}});}
            public void onRoom(RoomState r){callbacks.add(()->{running=r.phase==RoomPhase.RUNNING;if(!running){view=null;clearInput();}});}
            public void onView(PlayerView v){callbacks.add(()->view=v);}
            public void onResult(MatchResult r){callbacks.add(()->{running=false;clearInput();notice="Result: "+r.reason;});}
            public void onError(GatewayError e){callbacks.add(()->notice=e.code+": "+e.message);}
        });
    }
    private void joystick(Vector2 p) {
        double dx=(p.x-JOY_X)/JOY_R,dy=(p.y-JOY_Y)/JOY_R,length=Math.hypot(dx,dy);
        if(length<=0.15){moveX=moveY=0;return;}
        double strength=Math.min(1,(length-0.15)/0.85);
        moveX=dx/length*strength;moveY=dy/length*strength;
    }
    private void release(int pointer) {
        if(pointer==joystickPointer){joystickPointer=-1;moveX=moveY=0;}
        if(pointer==interactPointer){interactPointer=-1;interact=false;}
        gateway.submitInput(new InputFrame(moveX,moveY,interact,false));
    }
    private void clearInput(){joystickPointer=interactPointer=-1;moveX=moveY=0;interact=dropPulse=false;gateway.submitInput(InputFrame.ZERO);}

    @Override public void render() {
        Runnable next;while((next=callbacks.poll())!=null)next.run();
        gateway.submitInput(running?new InputFrame(moveX,moveY,interact,dropPulse):InputFrame.ZERO);
        dropPulse=false;
        if(gateway instanceof FakeGateway)((FakeGateway)gateway).advance(Gdx.graphics.getDeltaTime());
        Gdx.gl.glClearColor(0.035f,0.05f,0.08f,1);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        world.apply();
        double x=view==null?data.map.width/2.0:view.self.x,y=view==null?data.map.height/2.0:view.self.y;
        worldCamera.position.set((float)x,(float)y,0);worldCamera.update();
        shapes.setProjectionMatrix(worldCamera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.08f,0.12f,0.18f,1);shapes.rect(0,0,data.map.width,data.map.height);
        shapes.setColor(0.18f,0.25f,0.34f,1);
        for(WallCell w:data.map.walls)shapes.rect(w.col,w.row,1,1);
        shapes.setColor(0.15f,0.65f,0.55f,1);
        for(MapPoint p:data.map.deliveries)shapes.rect((float)p.x-.2f,(float)p.y-.2f,.4f,.4f);
        shapes.setColor(0.85f,0.56f,0.17f,1);
        for(MapPoint p:data.map.exits)shapes.rect((float)p.x-.3f,(float)p.y-.3f,.6f,.6f);
        if(view!=null) {
            if(view.thief!=null) {shapes.setColor(Color.GOLD);for(GroundMaterial m:view.thief.groundMaterials)shapes.circle((float)m.x,(float)m.y,.15f,16);}
            if(view.opponent!=null) {shapes.setColor(Color.RED);shapes.circle((float)view.opponent.x,(float)view.opponent.y,.25f,24);}
            shapes.setColor(view.role==Role.THIEF?Color.CYAN:Color.SKY);shapes.circle((float)view.self.x,(float)view.self.y,.25f,24);
        }
        shapes.end();
        ui.apply();shapes.setProjectionMatrix(uiCamera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.045f,0.07f,0.11f,1);shapes.rect(0,575,1280,145);
        button(30,592,205,52,selectedRole==Role.POLICE);button(250,592,205,52,selectedRole==Role.THIEF);button(485,592,250,52,running);
        shapes.setColor(0.12f,0.2f,0.29f,1);shapes.circle(JOY_X,JOY_Y,JOY_R,48);
        shapes.setColor(Color.CYAN);shapes.circle(JOY_X+(float)moveX*55,JOY_Y+(float)moveY*55,28,32);
        if(selectedRole==Role.THIEF){shapes.setColor(interact?Color.TEAL:new Color(.17f,.26f,.34f,1));shapes.circle(1125,145,70,48);shapes.setColor(.17f,.26f,.34f,1);shapes.circle(965,105,50,32);}
        shapes.end();
        batch.setProjectionMatrix(uiCamera.combined);batch.begin();
        font.setColor(Color.WHITE);font.draw(batch,"LIGHT CHASE  /  S0 SKELETON",30,695);
        font.setColor(Color.GOLD);font.draw(batch,"OFFLINE FIXTURE - no real match / no gameplay collisions",30,666);
        font.setColor(Color.WHITE);font.draw(batch,"POLICE",88,625);font.draw(batch,"THIEF",310,625);
        font.draw(batch,running?"BACK TO LOBBY":"START DEMO",510,625);
        font.draw(batch,"Status: "+connection,820,690);font.draw(batch,"Role: "+selectedRole,820,660);
        font.draw(batch,"MOVE",105,32);
        if(selectedRole==Role.THIEF){font.draw(batch,"HOLD",1096,150);font.draw(batch,"DROP",940,110);}
        font.draw(batch,notice,280,45);
        if(view!=null)font.draw(batch,String.format(java.util.Locale.ROOT,"Position %.2f, %.2f | requests: move / hold / drop",view.self.x,view.self.y),280,78);
        batch.end();
    }
    private void button(float x,float y,float w,float h,boolean active){shapes.setColor(active?new Color(.14f,.40f,.47f,1):new Color(.10f,.18f,.26f,1));shapes.rect(x,y,w,h);}
    @Override public void resize(int width,int height){world.update(width,height);ui.update(width,height,true);}
    @Override public void pause(){clearInput();gateway.background();}
    @Override public void resume(){if(data!=null){callbacks.clear();view=null;running=false;connectFixture();}}
    @Override public void dispose(){clearInput();gateway.close();if(shapes!=null)shapes.dispose();if(batch!=null)batch.dispose();if(font!=null)font.dispose();}
}
