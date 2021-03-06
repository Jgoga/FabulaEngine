package com.macbury.fabula.screens;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

import com.artemis.Entity;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.FPSLogger;
import com.badlogic.gdx.graphics.GL10;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.lights.BaseLight;
import com.badlogic.gdx.graphics.g3d.lights.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.lights.Lights;
import com.badlogic.gdx.graphics.g3d.materials.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.materials.Material;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.macbury.fabula.db.PlayerStartPosition;
import com.macbury.fabula.editor.WorldEditorFrame;
import com.macbury.fabula.editor.brushes.AutoTileBrush;
import com.macbury.fabula.editor.brushes.Brush;
import com.macbury.fabula.editor.brushes.EventBrush;
import com.macbury.fabula.editor.brushes.FoliageBrush;
import com.macbury.fabula.editor.brushes.LiquidBrush;
import com.macbury.fabula.editor.brushes.Brush.BrushType;
import com.macbury.fabula.editor.brushes.PassableBrush;
import com.macbury.fabula.editor.brushes.TerrainBrush;
import com.macbury.fabula.editor.tiles.AutoTileDebugFrame;
import com.macbury.fabula.editor.tree.GameTreeModel.BaseGameFolderNode;
import com.macbury.fabula.editor.undo_redo.ChangeManager;
import com.macbury.fabula.manager.G;
import com.macbury.fabula.manager.GameManager;
import com.macbury.fabula.map.Scene;
import com.macbury.fabula.terrain.Terrain;
import com.macbury.fabula.terrain.Terrain.TerrainDebugListener;
import com.macbury.fabula.terrain.foliage.FoliageSet;
import com.macbury.fabula.terrain.tile.Tile;
import com.macbury.fabula.utils.ActionTimer;
import com.macbury.fabula.utils.ActionTimer.TimerListener;
import com.macbury.fabula.utils.EditorCamController;
import com.macbury.fabula.utils.TopDownCamera;

public class WorldEditScreen extends BaseScreen implements InputProcessor, TimerListener, TerrainDebugListener {
  private static WorldEditScreen _shared;
  public String debugInfo = "";
  private static final String TAG = "WorldScreen";
  private static final float APPLY_BRUSH_EVERY = 0.02f;
  private TopDownCamera camera;
  private EditorCamController camController;
  private ActionTimer   brushTimer;
  private Brush         currentBrush;
  private TerrainBrush  terrainBrush;
  private AutoTileBrush autoTileBrush;
  private FoliageBrush  foliageBrush;
  private LiquidBrush   liquidBrush;
  private Scene scene;
  private Terrain terrain;
  private boolean isPaused;
  private boolean isDragging = false;
  private EventBrush eventBrush;
  private BitmapFont baseFont;
  private SpriteBatch uiSpriteBatch;
  private OrthographicCamera guiCamera;
  private ActionTimer infoTimer;
  private PassableBrush passableTileBrush;
  
  public WorldEditScreen(GameManager manager) {
    super(manager);
    _shared = this;
  }

  @Override
  public void dispose() {
    this.terrain.dispose();
  }
  
  @Override
  public void show() {
    Gdx.app.log(TAG, "Showed screen");
    G.shaders.createFB(Scene.MAIN_FRAME_BUFFER);
    this.brushTimer    = new ActionTimer(APPLY_BRUSH_EVERY, this);
    this.infoTimer     = new ActionTimer(1.0f, this);
    this.camera        = new TopDownCamera();
    this.baseFont      = G.db.getFont("base");
    this.uiSpriteBatch = new SpriteBatch();
    guiCamera          = new OrthographicCamera();
    guiCamera.setToOrtho(false);
    
    Gdx.app.log(TAG, "Initialized screen");
    G.shaders.add("terrain-editor", "terrain-editor.vert", "terrain-editor.frag");
    
    if (G.db.getPlayerStartPosition() == null) {
      newMap(100,100);
    } else {
      openMap(G.db.getPlayerStartPosition().getFileHandler().file());
    }
    
    
    this.infoTimer.start();
    this.camController = new EditorCamController(camera);
    InputMultiplexer inputMultiplexer = new InputMultiplexer(this, camController);
    Gdx.input.setInputProcessor(inputMultiplexer);
  }
  
  public void newMap(int width, int height) {
    String uuid  = UUID.randomUUID().toString();
    this.scene   = new Scene(null, uuid, width, height);
    this.scene.setCamera(camera);
    this.scene.setDebug(true);
    this.terrain = this.scene.getTerrain();
    terrain.setDebugListener(this);
    terrain.fillEmptyTilesWithDebugTile();
    terrain.buildSectors();
    camera.position.set(width/2, 17, height/2);
    camera.lookAt(width/2, 0, height/2);
    this.scene.initialize();
    createBrushes();
  }
  
  private void createBrushes() {
    terrainBrush      = new TerrainBrush(terrain);
    autoTileBrush     = new AutoTileBrush(terrain);
    passableTileBrush = new PassableBrush(terrain);
    eventBrush        = new EventBrush(terrain);
    liquidBrush       = new LiquidBrush(terrain);
    foliageBrush      = new FoliageBrush(terrain);
  }

  public void openMap(File file) {
    this.scene = Scene.open(file);
    
    if (this.scene == null) {
      Gdx.app.log(TAG, "Could not load file " + file.getAbsolutePath());
      newMap(100, 100);
      return;
    }
    
    this.scene.setCamera(camera);
    this.scene.setDebug(true);
    this.terrain = scene.getTerrain();
    terrain.setDebugListener(this);
    terrain.buildSectors();
    camera.position.set(terrain.getColumns()/2, 17, terrain.getRows()/2);
    camera.lookAt(terrain.getColumns()/2, 0, terrain.getRows()/2);
    this.scene.initialize();
    createBrushes();
    
    Gdx.app.postRunnable(new Runnable() {
      @Override
      public void run() {
        // TODO not working why i dont now
        PlayerStartPosition psp = G.db.getPlayerStartPosition(); 
        if (psp != null && psp.getUUID().equals(scene.getUID())) {
          Entity e = G.factory.buildStartPosition(G.db.getPlayerStartPosition());
          e.addToWorld();
        }
      }
    });
  }

  @Override
  public void hide() {
  }
  
  @Override
  public void pause() {
    this.isPaused = true;
  }
  
  @Override
  public void render(float delta) {
    Gdx.gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
    Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    
    this.brushTimer.update(delta);
    this.infoTimer.update(delta);
    camController.update();
    camera.update();
    this.scene.render(delta);
    //modelBatch.begin(camera);
    //modelBatch.render(cursorInstance);
    //modelBatch.end();
    
    
    guiCamera.update();
    this.uiSpriteBatch.setProjectionMatrix(guiCamera.combined);
    //uiSpriteBatch.begin();
    //  baseFont.draw(uiSpriteBatch, "Font test!", 10, 30);
    //uiSpriteBatch.end();
  }

  @Override
  public void resize(int width, int height) {
    G.shaders.resize(width, height, true);
    guiCamera.viewportWidth  = camera.viewportWidth  = Gdx.graphics.getWidth();
    guiCamera.viewportHeight = camera.viewportHeight = Gdx.graphics.getHeight();
    this.camera.update(true);
    this.guiCamera.update(true);
    this.guiCamera.position.set(guiCamera.viewportWidth/2, guiCamera.viewportHeight/2, 0);
  }
  
  @Override
  public void resume() {
    this.isPaused = false;
  }
  
  public TopDownCamera getCamera() {
    return camera;
  }

  @Override
  public boolean keyDown(int arg0) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean keyTyped(char arg0) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean keyUp(int arg0) {
    // TODO Auto-generated method stub
    return false;
  }
  
  Vector3 mouseTilePosition = new Vector3();
  private ChangeManager changeManager;
  private WorldEditorFrame worldEditorFrame;
  @Override
  public boolean mouseMoved(int x, int y) {
    Ray ray     = camera.getPickRay(x, y);
    Vector3 pos = terrain.getSnappedPositionForRay(ray, mouseTilePosition);
    
    if (pos != null && currentBrush != null) {
      currentBrush.setPosition(pos.x, pos.z);
    }
    return false;
  }

  @Override
  public boolean scrolled(int arg0) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean touchDown(int x, int y, int pointer, int button) {
    if (button == Buttons.LEFT && currentBrush != null) {
      Vector3 pos = getPositionForMouse(x, y);
      
      if (pos != null) {
        currentBrush.setStartPosition(pos.x, pos.z);
      }
      
      this.brushTimer.start();
      isDragging = true;
      return true;
    }
    return false;
  }

  @Override
  public boolean touchDragged(int x, int y, int pointer) {
    if (isDragging && currentBrush != null) {
      Vector3 pos = getPositionForMouse(x, y);
      
      if (pos != null) {
        currentBrush.applyStartPositionIfNotSetted(pos);
        currentBrush.setPosition(pos.x, pos.z);
      }
    }
    
    return false;
  }

  @Override
  public boolean touchUp(int x, int y, int pointer, int button) {
    boolean result = false;
    if (button == Buttons.LEFT && currentBrush != null) {
      isDragging  = false;
      Vector3 pos = getPositionForMouse(x, y);
      if (pos != null) {
        currentBrush.setPosition(pos.x, pos.z);
        currentBrush.applyBrush();
      }
      currentBrush.setStartPosition(null);
      this.brushTimer.stop();
      result = true;
    }
    
    if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)) {
      getContainerFrame().updateInspectorBean();
    }
    return result;
  }

  @Override
  public void onTimerTick(ActionTimer timer) {
    if (timer == infoTimer) {
      if (isPaused) {
        debugInfo = "Paused";
        return;
      }
      
      if (getCurrentBrush() != null) {
        debugInfo = "X: "+ getCurrentBrush().getPosition().x + " Y " + getCurrentBrush().getY() + " Z: " +  getCurrentBrush().getPosition().y + " " + getCurrentBrush().getStatusBarInfo() + " ";
      } else {
        debugInfo = "";
      }
      debugInfo += "FPS: "+ Gdx.graphics.getFramesPerSecond() + " Java Heap: " + (Gdx.app.getJavaHeap() / 1024) + " KB" + " Native Heap: " + (Gdx.app.getNativeHeap() / 1024);
      
    } else if (currentBrush != null && currentBrush.getBrushType() == BrushType.Pencil) {
      currentBrush.applyBrush();
    }
  }

  @Override
  public void onDebugTerrainConfigureShader(ShaderProgram shader) {
    if (currentBrush != null) {
      shader.setUniformf("u_brush_position", currentBrush.getPosition());
      shader.setUniformf("u_brush_size", currentBrush.getSize());
      shader.setUniformf("layer_style", EventBrush.class.isInstance(currentBrush) ? 1 : 0);
      if (currentBrush.getStartPosition() == null) {
        shader.setUniformi("u_brush_type", 0);
      } else {
        shader.setUniformf("u_brush_start_position", currentBrush.getStartPosition());
        shader.setUniformi("u_brush_type", currentBrush.getBrushShaderId());
      }
    }
  }

  public Brush getCurrentBrush() {
    return currentBrush;
  }

  public void setCurrentBrush(Brush currentBrush) {
    this.currentBrush = currentBrush;
    
    if (currentBrush != null) {
      this.currentBrush.setWorldEditScreen(this);
      this.currentBrush.setChangeManager(this.changeManager);
    }
    
  }

  public TerrainBrush getTerrainBrush() {
    return terrainBrush;
  }

  public AutoTileBrush getAutoTileBrush() {
    return autoTileBrush;
  }

  public Scene getScene() {
    return this.scene;
  }
  
  public Vector3 getPositionForMouse(float x, float y) {
    Ray ray     = camera.getPickRay(x, y);
    return terrain.getSnappedPositionForRay(ray, mouseTilePosition);
  }

  public void setChangeManager(ChangeManager changeManager) {
    this.changeManager = changeManager;
  }

  public void onDrop(BaseGameFolderNode node) {
    int x = Gdx.input.getX();
    int z = Gdx.input.getY();
    
    Gdx.app.log(TAG, "Dropped: " + node.toString());
  }

  public EventBrush getEventBrush() {
    return this.eventBrush;
  }

  public void setContainerFrame(WorldEditorFrame worldEditorFrame) {
    this.worldEditorFrame = worldEditorFrame;
  }

  public WorldEditorFrame getContainerFrame() {
    return this.worldEditorFrame;
  }

  public void resetCamera() {
    int width = terrain.getColumns();
    int height  = terrain.getRows();
    camera.position.set(width/2, 17, height/2);
    camera.lookAt(width/2, 0, height/2);
  }

  public PassableBrush getPassableBrush() {
    return this.passableTileBrush;
  }

  public LiquidBrush getLiquidBrush() {
    return liquidBrush;
  }

  public FoliageBrush getFoliageBrush() {
    return foliageBrush;
  }

  public void setFoliageBrush(FoliageBrush foliageBrush) {
    this.foliageBrush = foliageBrush;
  }

  public static WorldEditScreen shared() {
    return _shared;
  }

}
