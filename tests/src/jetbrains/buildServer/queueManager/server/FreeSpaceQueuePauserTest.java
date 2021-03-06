package jetbrains.buildServer.queueManager.server;

import jetbrains.buildServer.BaseTestCase;
import jetbrains.buildServer.queueManager.settings.Actor;
import jetbrains.buildServer.queueManager.settings.QueueState;
import jetbrains.buildServer.queueManager.settings.QueueStateManager;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.FileWatchingPropertiesModel;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.serverSide.impl.DiskSpaceWatcher;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.util.TestFor;
import org.jetbrains.annotations.NotNull;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.lib.legacy.ClassImposteriser;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Pauses build queue based on free space left
 *
 * @author Oleg Rybak (oleg.rybak@jetbrains.com)
 */
@TestFor(testForClass = {FreeSpaceQueuePauser.class}, issues = "TW-10787")
public class FreeSpaceQueuePauserTest extends BaseTestCase {

  private Mockery m;

  private DiskSpaceWatcher myDiskSpaceWatcher;

  private EventDispatcher<BuildServerListener> myDispatcher;

  private QueueStateManager myQueueStateManager;

  private QueueState myQueueState;

  /** class under test */
  private FreeSpaceQueuePauser pauser;

  @BeforeMethod
  @Override
  @SuppressWarnings("unchecked")
  protected void setUp() throws Exception {
    super.setUp();
    m = new Mockery() {{
      setImposteriser(ClassImposteriser.INSTANCE);
    }};
    myDiskSpaceWatcher = m.mock(DiskSpaceWatcher.class);
    myDispatcher = m.mock(EventDispatcher.class);
    myQueueState = m.mock(QueueState.class);
    myQueueStateManager = m.mock(QueueStateManager.class);
    m.checking(new Expectations() {{
      allowing(myDispatcher);
    }});
    pauser = new FreeSpaceQueuePauser(myDispatcher, myQueueStateManager, myDiskSpaceWatcher);
  }

  private void invoke() throws Exception {
    final Method m = pauser.getClass().getDeclaredMethod("check");
    m.setAccessible(true);
    m.invoke(pauser);
  }

  @Test
  public void testQueuePaused_DoNothing() throws Exception {
    m.checking(new Expectations() {{

      oneOf(myQueueStateManager).readQueueState();
      will(returnValue(myQueueState));

      oneOf(myQueueState).isQueueEnabled();
      will(returnValue(false));

      oneOf(myQueueState).getActor();
      will(returnValue(Actor.USER));

      oneOf(myDiskSpaceWatcher).getDirsNoSpace();
      will(returnValue(Collections.emptyMap()));
    }});
    invoke();
    m.assertIsSatisfied();
  }

  @Test
  public void testSpaceSufficient() throws Exception {
    m.checking(new Expectations() {{
      allowing(myDispatcher);

      oneOf(myQueueStateManager).readQueueState();
      will(returnValue(myQueueState));

      oneOf(myQueueState).isQueueEnabled();
      will(returnValue(true));

      oneOf(myDiskSpaceWatcher).getDirsNoSpace();
      will(returnValue(Collections.emptyMap()));
    }});
    invoke();
  }

  @Test
  @TestFor(issues = "TW-34816")
  public void testSpaceInsufficient_DoNotPauseQueue() throws Exception {
    final Map<String, Long> paths = new HashMap<String, Long>() {{
      put("path1", 51 * 1024 * 1024L);
    }};

    m.checking(new Expectations() {{
      allowing(myDispatcher);

      oneOf(myQueueStateManager).readQueueState();
      will(returnValue(myQueueState));

      oneOf(myQueueState).isQueueEnabled();
      will(returnValue(true));

      oneOf(myDiskSpaceWatcher).getDirsNoSpace();
      will(returnValue(paths));

      oneOf(myDiskSpaceWatcher).getThreshold();
      will(returnValue(1000L));

      exactly(1).of(same(myQueueStateManager)).method("writeQueueState");
    }});
    invoke();
  }

  @Test
  public void testSpaceInsufficient_PauseQueue() throws Exception {
    final Map<String, Long> paths = new HashMap<String, Long>() {{
      put("path1", 49 * 1024 * 1024L);
      put("path2", 51 * 1024 * 1024L);
    }};

    m.checking(new Expectations() {{
      allowing(myDispatcher);

      oneOf(myQueueStateManager).readQueueState();
      will(returnValue(myQueueState));

      oneOf(myQueueState).isQueueEnabled();
      will(returnValue(true));

      oneOf(myDiskSpaceWatcher).getDirsNoSpace();
      will(returnValue(paths));

      oneOf(myDiskSpaceWatcher).getThreshold();
      will(returnValue(1000L));

      exactly(1).of(same(myQueueStateManager)).method("writeQueueState");
    }});
    invoke();
  }

  @Test
  @TestFor(issues = "TW-33042")
  public void testAutoResume_Disabled_DoNothing() throws Exception {
    final String text = "teamcity.internal.properties.reread.interval.ms=100\n" +
            "teamcity.queuePauser.pauseOnNoDiskSpace=true\n" +
            "teamcity.queuePauser.resumeOnDiskSpace=false";
    changeTeamCityProperties(text);
    m.checking(new Expectations() {{
      allowing(myDispatcher);

      oneOf(myQueueStateManager).readQueueState();
      will(returnValue(myQueueState));

      oneOf(myQueueState).isQueueEnabled();
      will(returnValue(false));

      oneOf(myDiskSpaceWatcher).getDirsNoSpace();
      will(returnValue(Collections.emptyMap()));

    }});
    invoke();
  }

  @Test
  @TestFor(issues = "TW-33042")
  public void testAutoResume_Enabled_CanResume() throws Exception {
    final String text = "teamcity.internal.properties.reread.interval.ms=100\n" +
            "teamcity.queuePauser.pauseOnNoDiskSpace=true\n" +
            "teamcity.queuePauser.resumeOnDiskSpace=true";
    changeTeamCityProperties(text);
    m.checking(new Expectations() {{
      allowing(myDispatcher);

      oneOf(myQueueStateManager).readQueueState();
      will(returnValue(myQueueState));

      oneOf(myQueueState).isQueueEnabled();
      will(returnValue(false));

      oneOf(myDiskSpaceWatcher).getDirsNoSpace();
      will(returnValue(Collections.emptyMap()));

      oneOf(myQueueState).getActor();
      will(returnValue(Actor.FREE_SPACE_QUEUE_PAUSER));

      exactly(1).of(same(myQueueStateManager)).method("writeQueueState");

    }});
    invoke();
  }

  @Test
  @TestFor(issues = "TW-33042")
  public void testAutoResume_Enabled_WrongActor() throws Exception {
    final String text = "teamcity.internal.properties.reread.interval.ms=100\n" +
            "teamcity.queuePauser.pauseOnNoDiskSpace=true\n" +
            "teamcity.queuePauser.resumeOnDiskSpace=true";
    changeTeamCityProperties(text);
    m.checking(new Expectations() {{
      allowing(myDispatcher);

      oneOf(myQueueStateManager).readQueueState();
      will(returnValue(myQueueState));

      oneOf(myQueueState).isQueueEnabled();
      will(returnValue(false));

      oneOf(myDiskSpaceWatcher).getDirsNoSpace();
      will(returnValue(Collections.emptyMap()));

      oneOf(myQueueState).getActor();
      will(returnValue(Actor.USER));

    }});
    invoke();
  }

  @Test
  public void testDisabled_DoNothing() throws Exception {
    final String text = "teamcity.internal.properties.reread.interval.ms=100\n" +
            "teamcity.queuePauser.pauseOnNoDiskSpace=false";
    changeTeamCityProperties(text);
    m.checking(new Expectations() {{
      allowing(myDispatcher);
    }});
    invoke();
  }

  private void changeTeamCityProperties(@NotNull final String propsString) {
    try {
      final File myProps = createTempFile(propsString);
      final FileWatchingPropertiesModel myModel = new FileWatchingPropertiesModel(myProps);
      final Field field = TeamCityProperties.class.getDeclaredField("ourModel");
      field.setAccessible(true);
      field.set(TeamCityProperties.class, myModel);
      FileUtil.writeFileAndReportErrors(myProps, propsString);
      myModel.forceReloadProperties();
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }


}
