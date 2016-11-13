import processing.core.*; 
import processing.data.*; 
import processing.event.*; 
import processing.opengl.*; 

import processing.net.Client; 
import processing.net.*; 
import java.util.regex.Matcher; 
import java.util.regex.Pattern; 
import java.util.regex.Pattern; 
import java.lang.reflect.InvocationTargetException; 
import java.lang.reflect.Method; 
import java.util.Iterator; 

import java.util.HashMap; 
import java.util.ArrayList; 
import java.io.File; 
import java.io.BufferedReader; 
import java.io.PrintWriter; 
import java.io.InputStream; 
import java.io.OutputStream; 
import java.io.IOException; 

public class orbit_game extends PApplet {



String serverAddress;
int    serverPort;

Client client;
int    pingPongReceiveTime;
int    pingPongSendTime;

int gameWidth;
int gameHeight;
int gameCenterX;
int gameCenterY;

FSM   game;
State gameStart   = new State(this, "enterStart",   "runStart",   "exitStart");
State gamePlaying = new State(this, "enterPlaying", "runPlaying", "exitPlaying");
State gameOver    = new State(this, "enterOver",    "runOver",    "exitOver");

float rotationRps;
int   rotationDirection;

/**
 * Settings is the hook designed for setting the window size based on variables.
 * Here we interrogate the command line parameters, and set the server address, 
 * server port, game width and game height. There's also a `-f` flag representing 
 * full screen. While the server address and port are required parameters, the 
 * game width and game height are not.
 *
 * Note that at settings time, very little of Processing functionality is 
 * available.
 */
public void settings() {

    this.gameWidth = defaultGameWidth;
    this.gameHeight = defaultGameHeight;
    boolean displayFullScreen = false;

    // args may be null
    if (this.args != null) {

        if (this.args.length >= 1) {
            this.serverAddress = this.args[0];
        }

        if (this.args.length >= 2) {
            this.serverPort = PApplet.parseInt(this.args[1]);
        }

        if (this.args.length >= 3) {
            this.gameWidth = PApplet.parseInt(this.args[2]);
        }

        if (args.length >= 4) {
            this.gameHeight = PApplet.parseInt(this.args[3]);
        }

        if (
            this.args[this.args.length - 1] == "-f"
            || this.args[this.args.length - 1] == "--fullscreen"
        ) {
            this.gameWidth = this.displayWidth;
            this.gameHeight = this.displayHeight;
            displayFullScreen = true;
        }

    }

    if (!displayFullScreen) {
        size(this.gameWidth, this.gameHeight);
    } else {
        fullScreen();
    }

    this.gameCenterX = this.gameWidth / 2;
    this.gameCenterY = this.gameHeight / 2;

}

/**
 * Override the exit handler, so we close the client connection if its available.
 */
public void exit() {

    this.clientShutdown(this.client);
    super.exit();

}

/**
 * Sets up the game.
 */
public void setup() { 

    // ints cannot be null, so 0 is the sentinel value of serverPort
    if (
        this.serverAddress == null 
        || this.serverPort == 0 
    ) {
        // overrided exit is only available at setup and beyond
        println("Server Address and Server Port is Needed");
        exit();
        // exit doesn't return immediately, but by returning here, it will return immediately
        return;
    }

    this.client = this.clientEstablish(this.serverAddress, this.serverPort);
    if (this.client == null) {
        println("Game Client Couldn't Connect");
        exit();
        return;
    }

    // initialise both ping pong send and receive time to the current time
    int currentTime = this.getCurrentTime();
    this.pingPongSendTime = currentTime;
    this.pingPongReceiveTime = currentTime;

    this.game = new FSM(this.gameStart);

}

/**
 * Event loop
 */
public void draw() {

    int currentTime = this.getCurrentTime();

    boolean pongStatus = this.clientPongCheck(
        this.client, 
        this.pingPongTimeout, 
        this.pingPongReceiveTime, 
        currentTime
    );

    if (!pongStatus) {
        println("Game Client Lost Connection, Restarting Connection");
        this.clientShutdown(client);
        this.client = this.clientEstablish(this.serverAddress, this.serverPort);
        if (this.client == null) {
            println("Game Client Couldn't Reconnect");
            exit();
        }
    }

    boolean pingStatus = this.clientPingCheck(
        this.client, 
        this.pingPongInterval,
        this.pingPongSendTime,
        currentTime
    );

    if (pingStatus) {
        this.pingPongSendTime = currentTime;
    }

    ClientData clientData = this.clientRead(this.client, this.messageProtocol, this.rpsAndDirTokenRegex);

    if (clientData != null) {
        if (clientData.rps != null && clientData.direction != null) {
            this.rotationRps         = clientData.rps;
            this.rotationDirection   = clientData.direction; 
        }
        this.pingPongReceiveTime = currentTime;
    }

    this.game.update();

}

/**
 * Get the current time since this program started in seconds.
 */
public int getCurrentTime() {

    return millis() / 1000;

}




StringBuilder messageBuffer = new StringBuilder();

class ClientData {

    Float rps;
    Integer direction;

    ClientData(Float rps, Integer direction) {
        this.rps = rps;
        this.direction = direction;
    }

}

/**
 * Established a new TCP connection to the Orbit Server.
 * Since Processing's Client doesn't throw exceptions, it'll check if the connection worked.
 * It also clears any data in the client buffer.
 */
public Client clientEstablish(String serverAddress, int serverPort) {
    
    Client client = new Client(this, serverAddress, serverPort);
    if (!client.active()) {
        return null;
    }
    client.clear();
    return client;

}

/**
 * Shutsdown the client if is still active, will also clear the buffer.
 */
public void clientShutdown(Client client) {

    if (client != null && client.active()) {
        client.clear();
        client.stop();
    }
    
}

/**
 * Checks if it is the right time to ping the Orbit Server for liveness.
 * Returns true if it checked, returns false it hasn't checked.
 * The caller needs to update the pingPongSendTime if true is returned.
 */
public boolean clientPingCheck(Client client, int pingPongInterval, int pingPongSendTime, int currentTime) {

    if (!client.active()) {
        return false;
    }

    if (currentTime >= (pingPongSendTime + pingPongInterval)) {
        client.write("SPINGE");
        return true;
    }

    return false;

}

/**
 * Checks if the connection hasn't timed out according to the ping pong protocol.
 * Also checks if the connection is still active.
 * The caller should handle a timed-out or closed connection appropriately.
 */
public boolean clientPongCheck(Client client, int pingPongTimeout, int pingPongReceiveTime, int currentTime) {

    if (!client.active()) {
        return false;
    }

    if (currentTime >= (pingPongReceiveTime + pingPongTimeout)) {
        return false; 
    }

    return true;

}

/**
 * Polls the connection and tries to acquire a message frame.
 * Will buffer the byte-stream content until a message frame is available.
 * This is non-blocking, it will just return what is available.
 * The caller must update the pingPongReceiveTime if any ClientData is returned.
 * However the properties of ClientData may be null, if the message was not a RPS and direction message.
 */
public ClientData clientRead(Client client, Pattern messageProtocol, String rpsAndDirRegex) {

    Matcher lexer;
    String token;
    String[] rpsAndDirMatches = null;
    boolean acquired = false;

    // if the client connection was dropped, just return null
    if (!client.active()) {
        return null;
    }

    if (client.available() > 0) {

        this.messageBuffer.append(client.readString());
        lexer = messageProtocol.matcher(this.messageBuffer.toString());

        if (lexer.find()) {

            token = lexer.group(1);
            if (token != null) {

                // we have acquired a message token
                acquired = true;

                // process the message token
                rpsAndDirMatches = match(token, rpsAndDirRegex);
                if (rpsAndDirMatches == null) {
                    switch (token) {
                        case "PING":
                            client.write("SPONGE");
                        break;
                        case "PONG":
                            // pass
                        break;
                    }
                }

            }

            this.messageBuffer.delete(lexer.start(), lexer.end());

        }

    }

    if (acquired) {

        // if just get a PING/PONG message, then its still client data, but no rps and dir updates
        if (rpsAndDirMatches != null) {

            return new ClientData(
                PApplet.parseFloat(rpsAndDirMatches[0]), 
                PApplet.parseInt(rpsAndDirMatches[1])
            );

        } else {

            return new ClientData(
                null,
                null
            );

        }

    } else {

        return null;

    }

}


/////////////////////
// Visual Settings //
/////////////////////

// pixel radius of the balloon
final int hotBalloonSize = 25;
// default screen width
final int defaultGameWidth = 500;
// default screen height
final int defaultGameHeight = 500;

///////////////////
// Game Settings //
///////////////////

// assume pixels are meters
// factor conversion of RPS to force in newtons
final float rotationRPSToForceFactor = 4.0f;
// gravity in pixels/second^2
final float gravity = -9.8f;
// weight in kg
final float hotBalloonWeight = 10;
// velocity in pixels/second
final float hotBalloonHoriVelocity = 1.0f;
// the random range of distance between walls
final float wallMinIntervalFactor = 0.1f;
final float wallMaxIntervalFactor = 0.5f;
// the random range of gap height for each wall
final float wallMinGapFactor = 0.3f;
final float wallMaxGapFactor = 0.8f;
// wall width
final float wallMinWidthFactor = 0.1f;
final float wallMaxWidthFactor = 0.2f;

//////////////////////
// Network Settings //
//////////////////////

final int pingPongTimeout = 5;
final int pingPongInterval = 2; 
final Pattern messageProtocol = Pattern.compile("^(?:[^S]*)(?:S(.*?)E)?");
final String rpsAndDirTokenRegex = "(-?[0-1]):((?:[0-9]*[.])?[0-9]+)";



class FSM {

    State currentState;

    FSM(State initialState) {
        currentState = initialState;
    }

    public void update() {
        currentState.executeFunction();
    }

    public State getCurrentState(){
        return currentState;
    }

    public boolean isInState(State state){
        return currentState == state;
    }

    public void transitionTo(State newState) {
        currentState.exitFunction();
        currentState = newState;
        currentState.enterFunction();
    }

}

class State {

    PApplet parent;
    Method enterFunction;
    Method executeFunction;
    Method exitFunction;

    State(PApplet p, String enterFunctionName, String executeFunctionName, String exitFunctionName) {
    
        parent = p;
        Class sketchClass = parent.getClass();
        try { 
            enterFunction = sketchClass.getMethod(enterFunctionName);
            executeFunction = sketchClass.getMethod(executeFunctionName);
            exitFunction = sketchClass.getMethod(exitFunctionName);
        } catch(NoSuchMethodException e) {
            println("One of the state transition function is missing.");
        }

    }

    public void enterFunction() {

        try {
            enterFunction.invoke(parent);
        } catch(IllegalAccessException e) {
            println("State enter function is missing or something is wrong with it.");
        } catch(InvocationTargetException e) {
            println("State enter function is missing.");
        }
    
    }

    public void executeFunction() {
        
        try {
            executeFunction.invoke(parent);
        } catch(IllegalAccessException e) {
            println("State execute function is missing or something is wrong with it.");
        } catch(InvocationTargetException e) {
            println("State execute function is missing.");
        }

    }

    public void exitFunction() {

        try {
            exitFunction.invoke(parent);
        } catch(IllegalAccessException e) {
            println("State exit function is missing or something is wrong with it.");
        } catch(InvocationTargetException e) {
            println("State exit function is missing.");
        }
    
    }

}


float hotBalloonVertVelocity;
int hotBalloonX, hotBalloonY;
int score;

PGraphics startScreen;
PGraphics playBackground;
PGraphics hotBalloon;

int wallInterval;
int wallUnpassedIndex;
ArrayList<Wall> walls = new ArrayList<Wall>(); 

class Wall {

    PGraphics layer;
    int position;
    int width;
    int gapPosition;
    int gapHeight;

    Wall(PGraphics layer, int position, int width, int gapPosition, int gapHeight) {
        this.layer = layer;
        this.position = position;
        this.width = width;
        this.gapPosition = gapPosition;
        this.gapHeight = gapHeight;
    }

}

/**
 * Key Event Handler.
 * This is only applied at the start and over states.
 */
public void keyPressed() { 

    State currentState = this.game.getCurrentState();

    if (currentState == this.gameStart) {
        this.game.transitionTo(this.gamePlaying);
    } else if (currentState == this.gameOver) {
        this.game.transitionTo(this.gameStart);
    }

}

////////////////////////////////
// Game Start State Functions //
////////////////////////////////

public void enterStart() {

    background(0);
    if (this.startScreen == null) {
        this.startScreen = this.createStartScreen(this.gameWidth, this.gameHeight);
    }
    imageMode(CORNER);
    image(this.startScreen, 0, 0);

}

public void runStart() {
    // nothing to do here
}

public void exitStart() {
    // nothing to do here
}

//////////////////////////////////
// Game Playing State Functions //
//////////////////////////////////

public void enterPlaying() {

    // reset the balloon to the center with no initial velocity
    this.hotBalloonX = this.gameCenterX;
    this.hotBalloonY = this.gameCenterY;
    this.hotBalloonVertVelocity = 0;
    // reset the walls
    this.wallUnpassedIndex = 0;
    this.wallInterval = 0;
    this.walls.clear();
    // reset the score
    this.score = 0;


    // create the layer assets required by the game
    if (this.playBackground == null) {
        this.playBackground = this.createPlayBackground(this.gameWidth, this.gameHeight);
    }

    if (this.hotBalloon == null) {
        this.hotBalloon = this.createHotBalloon(this.hotBalloonSize);
    }

}

public void runPlaying() {

    // reset the entire scene frame buffer
    // this is the common way in graphics programming
    background(0);

    imageMode(CORNER);
    image(this.playBackground, 0, 0);

    // the duration of 1 frame is roughly 1/60th of a second
    float timeIntervalFor1Frame = 1.0f / this.frameRate;

    // get the final velocity after acceleration
    float hotBalloonVertVelocityFinal = this.accelerateHotBalloonVert(
        this.hotBalloonVertVelocity,
        this.rotationRps,
        this.rotationDirection, 
        timeIntervalFor1Frame 
    );

    // decide whether the balloon is moving up or down
    // need to reverse the distance because origin is top-left in processing
    this.hotBalloonY = this.hotBalloonY - this.moveHotBalloon(
        this.hotBalloonVertVelocity, 
        hotBalloonVertVelocityFinal, 
        timeIntervalFor1Frame
    );

    // the final velocity becomes the initial velocity for the next frame
    this.hotBalloonVertVelocity = hotBalloonVertVelocityFinal;

    // bound the balloon by the ceiling and the floor
    this.hotBalloonY = this.boundByCeilingAndFloor(this.hotBalloonY, this.hotBalloon.height, this.gameHeight);

    // constant horizontal motion
    // the horizontal movement is actually applied to the walls
    // since the balloon stays in the center of the screen horizontally
    int horiDistance = this.moveHotBalloon(
        this.hotBalloonHoriVelocity, 
        this.hotBalloonHoriVelocity, 
        timeIntervalFor1Frame 
    );

    // update the walls (add new ones and delete out of screen ones)
    this.wallsUpdate(this.walls, horiDistance);

    // repaint each wall
    for (Wall wall : this.walls) {
        imageMode(CORNER);
        image(wall.layer, wall.position, 0);
    }

    // repaint the balloon
    imageMode(CENTER);
    image(this.hotBalloon, this.hotBalloonX, this.hotBalloonY);

    // if 2, we hit a wall, if 1 we passed a wall, if 0 nothing happened
    int wallStatus = this.meetTheWalls(
        this.walls, 
        this.hotBalloonX, 
        this.hotBalloonY, 
        this.hotBalloon 
    );
    if (wallStatus == 2) {
        this.game.transitionTo(this.gameOver);
    } else if (wallStatus == 1) {
        // increment score when we passed a wall
        this.score++;
    }

}

public void exitPlaying() {
    // nothing to do here
}

///////////////////////////////
// Game Over State Functions //
///////////////////////////////

public void enterOver() {

    background(0);
    // this screen changes each time, so its not kept statically
    PGraphics overScreen = this.createOverScreen(this.gameWidth, this.gameHeight, this.score);
    imageMode(CORNER);
    image(overScreen, 0, 0);

}

public void runOver() {
    // nothing to do
}

public void exitOver() {
    // nothing to do
}

//////////////////////
// Moving Functions //
//////////////////////

public float accelerateHotBalloonVert(
    float vertVelocity, 
    float rps, 
    int rpsDirection, 
    float time
) {

    // if rpsDirection is 0, then this results in 0 acceleration
    float liftForce = this.rotationRPSToForceFactor * (rpsDirection * rps);

    // via F = M * A
    float liftAcceleration = liftForce / this.hotBalloonWeight;

    // resolve against gravity
    float resultantAcceleration = this.gravity + liftAcceleration;

    // vf = vi + a * t
    float finalVelocity = vertVelocity + resultantAcceleration * time;

    return finalVelocity;

}

public int moveHotBalloon(float initialVelocity, float finalVelocity, float time) {

    // d = ((vi + vf) / 2) * t
    int distance = round((initialVelocity + finalVelocity / 2.0f) * time);
    return distance;

}

public int boundByCeilingAndFloor(int objectY, int objectHeight, int boundingHeight) {

    int boundedY;
    int objectRadius = round(objectHeight / 2.0f);
    
    // ceiling bound
    boundedY = max(objectY - objectRadius, 0) + objectRadius;
    // floor bound
    boundedY = min(objectY + objectRadius, boundingHeight) - objectRadius;

    return boundedY;

}

///////////
// Walls //
///////////

public void wallsUpdate(ArrayList<Wall> walls, int distanceMoved) {

    if (walls.isEmpty()) {

        this.wallAdd(walls, this.gameWidth);
    
    } else {
        
        // iterate over all walls
        // delete the ones that are out of the window 
        // and shift the ones that are still in the window
        for (Iterator<Wall> iter = walls.iterator(); iter.hasNext();) {
            
            Wall wall = iter.next();
            int wallRightSide = wall.position + wall.width;
            
            if (wallRightSide - distanceMoved <= 0) {
                iter.remove();
                this.wallUnpassedIndex--;
            } else {
                wall.position = wall.position - distanceMoved;
            }

        }

        // add a new wall if the wall interval is satisfied by the shift of distanceMoved
        Wall lastWall = walls.get(walls.size() - 1);
        int lastWallRightSide = lastWall.position + lastWall.width;
        if (lastWallRightSide > this.wallInterval) {

            this.wallAdd(walls, lastWallRightSide + this.wallInterval);
            
            // generate a new wall interval
            this.wallInterval = round(random(
                this.wallMinIntervalFactor * this.gameWidth,
                this.wallMaxIntervalFactor * this.gameWidth
            ));

        }

    }

}

public void wallAdd(ArrayList<Wall> walls, int newWallPosition) {

    int[] newWallParams = this.generateWallParameters();
    PGraphics newWallLayer = this.createWall(newWallParams[0], this.gameHeight, newWallParams[1], newWallParams[2]);
    
    walls.add(new Wall(
        newWallLayer, 
        newWallPosition, 
        newWallParams[0], // width
        newWallParams[1], // gap position
        newWallParams[2]  // gap height
    ));

}

public int[] generateWallParameters() {

    int wallWidth = round(random(
        this.wallMinWidthFactor * this.gameWidth,
        this.wallMaxWidthFactor * this.gameWidth
    ));

    int gapHeight = round(random(
        this.wallMinGapFactor * this.gameHeight,
        this.wallMaxGapFactor * this.gameHeight
    ));

    // this position is the Y coordinate from where the gapStarts
    int gapPosition = round(random(0, this.gameHeight - gapHeight));

    return new int[] { wallWidth, gapPosition, gapHeight };

}

public int meetTheWalls (ArrayList<Wall> walls, int hotBalloonX, int hotBalloonY, PGraphics hotBalloon) {

    // return 0, 1 or 2
    // if 2, we hit a wall, if 1 we passed a wall, if 0 nothing happened

    if (walls.isEmpty()) {
        return 0;
    }

    Wall wall = walls.get(this.wallUnpassedIndex);

    // there are 3 objects we care about when detecting collision here
    // the first object is the hotBalloon
    // the second object is the top half of wall
    // the third object is the bottom half of the wall
    // all 3 objects can be considered as rectangles
    // so we shall use the axis-aligned bounding box algorithm twice for each half of the wall
    
    // however note that this does not detect if 
    // the travel path of the hotBalloon intersects the travel path of the walls
    // this is because it's only detecting if there's an overlap in the bounding boxes of the objects
    // to detect intersection along a path, we would require a more sophisticated collision detection algorithm
    // http://gamedev.stackexchange.com/a/55991

    // find out the rounding mode of center-aligned odd widths in Processing Java
    int hotBalloonCornerX = hotBalloonX - round(hotBalloon.width / 2.0f);
    int hotBalloonCenterY = hotBalloonY - round(hotBalloon.height / 2.0f);
    int hotBalloonWidth = hotBalloon.width;
    int hotBalloonHeight = hotBalloon.height;

    int wallTopHalfX = wall.position;
    int wallTopHalfY = 0;
    int wallTopHalfWidth = wall.width;
    int wallTopHalfHeight = wall.gapPosition;

    int wallBottomHalfX = wall.position;
    int wallBottomHalfY = wall.gapPosition + wall.gapHeight;
    int wallBottomHalfWidth = wall.width;
    int wallBottomHalfHeight = wall.layer.height - wallBottomHalfY;

    if (
        hotBalloonCornerX < wallTopHalfX + wallTopHalfWidth &&  // the first left side has to be less than the second right side
        hotBalloonCornerX + hotBalloonWidth > wallTopHalfX &&   // the first right side has to be greater than the second left side
        hotBalloonCenterY > wallTopHalfY + wallTopHalfHeight && // the first top side has to be greater than the second bottom side
        hotBalloonCenterY + hotBalloonHeight < wallTopHalfY     // the first bottom side has to be less than the second top side
    ) {
    
        // collidied with top half
        this.wallUnpassedIndex++;
        return 2;

    }

    if (
        hotBalloonCornerX < wallBottomHalfX + wallBottomHalfWidth &&  // the first left side has to be less than the second right side
        hotBalloonCornerX + hotBalloonWidth > wallBottomHalfX &&      // the first right side has to be greater than the second left side
        hotBalloonCenterY > wallBottomHalfY + wallBottomHalfHeight && // the first top side has to be greater than the second bottom side
        hotBalloonCenterY + hotBalloonHeight < wallBottomHalfY        // the first bottom side has to be less than the second top side
    ) {
    
        // collided with bottom half
        this.wallUnpassedIndex++;
        return 2;

    }

    // if we didn't collide with the wall, did the pass the wall yet?
    // passing the wall simply means the hotBalloon left side is greater than the wall's right side
    if (
        hotBalloonCornerX > wall.position + wall.width
    ) {

        this.wallUnpassedIndex++;
        return 1;

    } else {

        // no passing, so nothing happens
        return 0;

    }

}
/**
 * Create Start State Screen
 */
public PGraphics createStartScreen(int width, int height) {

    int centerX = round(width / 2.0f);
    int centerY = round(height / 2.0f);
    PGraphics screen = createGraphics(width, height);
    screen.beginDraw();
    screen.background(251, 185, 1);
    screen.textAlign(CENTER);
    screen.text("Press any key to start", centerX, centerY);
    screen.endDraw();
    return screen;

}

/**
 * Create Play State Background
 */
public PGraphics createPlayBackground(int width, int height) {

    int hillCenterX = round(width / 2.0f);
    int hillCenterY = round(0.7f * height);
    int hillWidth = round(1.3f * width);
    int hillHeight = round(0.2f * height);

    int groundWidth = width;
    int groundHeight = round(0.26f * height);

    PGraphics background = createGraphics(width, height);

    background.beginDraw();

    // sky
    background.background(198, 226, 255);

    // hill
    background.fill(0, 139, 69);
    background.noStroke();
    background.ellipseMode(CENTER);
    background.ellipse(hillCenterX, hillCenterY, hillWidth, hillHeight);
    
    // ground
    background.fill(162, 205, 90);
    background.noStroke();
    background.rectMode(CORNER);    
    background.rect(0, background.height - groundHeight, groundWidth, groundHeight);
    
    background.endDraw();
    
    return background;

}


/**
 * Draw a hot balloon in to a PGraphics frame buffer layer.
 * The size parameter is the diameter of the balloon part of the hot air balloon.
 * All other dimensions will be sized proportionally to the size parameter.
 */
public PGraphics createHotBalloon(int size) {

    int layerSize = 2 * size;
    int layerCenterX = round(layerSize / 2.0f);
    int layerCenterY = round(layerSize / 2.0f);

    int balloonSize = size;
    int balloonMarker1Width = round(0.8f * balloonSize);
    int balloonMarker1Height = balloonSize;
    int balloonMarker2Width = round(0.6f * balloonSize);
    int balloonMarker2Height = balloonSize;

    int rope1StartXOffset = -round(0.48f * balloonSize);
    int rope1StartYOffset = 0;
    int rope1EndXOffset = round(0.04f * balloonSize);
    int rope1EndYOffset = round(0.8f * balloonSize);
    
    int rope2StartXOffset = round(0.4f * balloonSize);
    int rope2StartYOffset = 0;
    int rope2EndXOffset = -round(0.08f * balloonSize);
    int rope2EndYOffset = round(0.8f * balloonSize);

    int basketXOffset = 0;
    int basketYOffset = round(0.8f * balloonSize);
    int basketSize = round(0.4f * balloonSize);
    int basketMarker1Width = round(0.32f * balloonSize);
    int basketMarker1Height = basketSize;
    int basketMarker2Width = round(0.24f * balloonSize);
    int basketMarker2Height = basketSize;
    
    PGraphics hotBalloon = createGraphics(layerSize, layerSize);
    
    hotBalloon.beginDraw();
    
    // ropes
    hotBalloon.strokeWeight(1);
    hotBalloon.stroke(94, 38, 18);
    hotBalloon.line(
        layerCenterX + rope1StartXOffset, layerCenterY + rope1StartYOffset, 
        layerCenterX + rope1EndXOffset,   layerCenterY + rope1EndYOffset
    );
    hotBalloon.line(
        layerCenterX + rope2StartXOffset, layerCenterY + rope2StartYOffset, 
        layerCenterX + rope2EndXOffset,   layerCenterY + rope2EndYOffset
    );

    // balloon
    hotBalloon.fill(255, 48, 48);
    hotBalloon.noStroke();
    hotBalloon.ellipseMode(CENTER);
    hotBalloon.ellipse(
        layerCenterX, layerCenterY, 
        balloonSize, balloonSize
    );
    
    hotBalloon.fill(255, 255, 255);
    hotBalloon.ellipseMode(CENTER);
    hotBalloon.ellipse(
        layerCenterX, layerCenterY, 
        balloonMarker1Width, balloonMarker1Height
    );
    
    hotBalloon.fill(255, 48, 48);
    hotBalloon.ellipseMode(CENTER);
    hotBalloon.ellipse(
        layerCenterX, layerCenterY, 
        balloonMarker2Width, balloonMarker2Height
    );

    // basket
    hotBalloon.fill(139, 125, 107);
    hotBalloon.rectMode(CENTER);
    hotBalloon.rect(
        layerCenterX + basketXOffset, layerCenterY + basketYOffset, 
        basketSize, basketSize
    );
    
    hotBalloon.fill(139, 69, 19);
    hotBalloon.rectMode(CENTER);
    hotBalloon.rect(
        layerCenterX + basketXOffset, layerCenterY + basketYOffset, 
        basketMarker1Width, basketMarker1Height
    );
    
    hotBalloon.fill(139, 125, 107);
    hotBalloon.rectMode(CENTER);
    hotBalloon.rect(
        layerCenterX + basketXOffset, layerCenterY + basketYOffset, 
        basketMarker2Width, basketMarker2Height
    );

    hotBalloon.endDraw();

    return hotBalloon;
    
}

/**
 * Draw a single wall. The width and height is the width and height of the entire wall including the gaps.
 * The gap position is the Y-position from the top to the beginningof the gap.
 * The gap height is the size of the gap opening.
 */
public PGraphics createWall(int width, int height, int gapPosition, int gapHeight) {

    PGraphics wall = createGraphics(width, height);

    wall.beginDraw();
    wall.rectMode(CORNER);
    wall.strokeCap(ROUND);
    wall.fill(color(240, 248, 255));

    // top-half of the wall
    // includes bottom corner radius
    wall.rect(
        0, 
        0, 
        width, 
        gapPosition, 
        0, 0, 
        50, 50
    ); 

    // bottom-half of the wall
    // includes top corner radius
    wall.rect(
        0, 
        gapPosition + gapHeight, 
        width, 
        height, 
        50, 50, 
        0, 0
    );

    wall.endDraw();
    return wall;

}

/**
 * Create Over State Screen
 */
public PGraphics createOverScreen(int width, int height, int score) {

    int centerX = round(width / 2.0f);
    int centerY = round(height / 2.0f);

    PGraphics screen = createGraphics(width, height);

    screen.beginDraw();

    screen.background(251, 185, 1);
    
    screen.fill(255);

    screen.textSize(12);
    screen.textAlign(CENTER);
    screen.text("Your Score:", centerX, centerY);
    
    screen.textSize(130);
    screen.textAlign(CENTER);
    screen.text(score, centerX, centerY + 120);
    
    screen.textSize(15);
    screen.text("Press any key to restart", centerX, centerY + 140);

    screen.endDraw();
    
    return screen;

}
  static public void main(String[] passedArgs) {
    String[] appletArgs = new String[] { "orbit_game" };
    if (passedArgs != null) {
      PApplet.main(concat(appletArgs, passedArgs));
    } else {
      PApplet.main(appletArgs);
    }
  }
}
