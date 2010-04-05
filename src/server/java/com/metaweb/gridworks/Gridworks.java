package com.metaweb.gridworks;

import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.webapp.WebAppContext;
import org.mortbay.util.Scanner;

import com.metaweb.util.logging.IndentingLayout;
import com.metaweb.util.signal.SignalHandler;
import com.metaweb.util.threads.ThreadPoolExecutorAdapter;

public class Gridworks {
    
    static private final String version = "1.0a";
    static private File tempDir;
    
    private static Logger root = Logger.getRootLogger();
    private static Logger logger = Logger.getLogger("com.metaweb.gridworks");

    public static void log(String message) {
        logger.info(message);
    }

    public static void info(String message) {
        logger.info(message);
    }
    
    public static void error(String message, Throwable t) {
        logger.error(message, t);
    }

    public static void warn(String message) {
        logger.warn(message);
    }

    public static void warn(String message, Throwable t) {
        logger.warn(message, t);
    }
    
    public static String getVersion() {
        return version;
    }
    
    public static File getTempFile(String name) {
        return new File(tempDir, name);
    }
    
    public static void main(String[] args) throws Exception  {
        
        // tell jetty to use SLF4J for logging instead of its own stuff
        System.setProperty("VERBOSE","false");
        System.setProperty("org.mortbay.log.class","org.mortbay.log.Slf4jLog");

        // tell macosx to keep the menu associated with the screen
        System.setProperty("apple.laf.useScreenMenuBar", "true");  
        System.setProperty("com.apple.eawt.CocoaComponent.CompatibilityMode", "false"); 

        // initialize the log4j system
        Appender console = new ConsoleAppender(new IndentingLayout());
        root.setLevel(Level.ALL);
        root.addAppender(console);

        Logger jetty_logger = Logger.getLogger("org.mortbay.log");
        jetty_logger.setLevel(Level.WARN);

        tempDir = new File(Configurations.get("gridworks.temp","temp"));
        if (!tempDir.exists()) tempDir.mkdirs();
        
        Gridworks gridworks = new Gridworks();
        
        gridworks.init(args);
    }

    public void init(String[] args) throws Exception {

        int port = Configurations.getInteger("gridworks.port",3333);
        String host = Configurations.get("gridworks.host","127.0.0.1");
        
        GridworksServer server = new GridworksServer();
        server.init(host,port);

        GridworksClient client = new GridworksClient();
        client.init(host,port);
        
        // hook up the signal handlers
        new ShutdownSignalHandler("TERM", server);
    }
}

/* -------------- Gridworks Server ----------------- */

class GridworksServer extends Server {
    
    private ThreadPoolExecutor threadPool;
    
    public void init(String host, int port) throws Exception {
        int maxThreads = Configurations.getInteger("gridworks.queue.size", 10);
        int maxQueue = Configurations.getInteger("gridworks.queue.max_size", 50);
        long keepAliveTime = Configurations.getInteger("gridworks.queue.idle_time", 60);

        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>(maxQueue);
        
        threadPool = new ThreadPoolExecutor(maxThreads, maxQueue, keepAliveTime, TimeUnit.SECONDS, queue);

        this.setThreadPool(new ThreadPoolExecutorAdapter(threadPool));
        
        Connector connector = new SocketConnector();
        connector.setPort(port);
        connector.setHost(host);
        connector.setMaxIdleTime(Configurations.getInteger("gridworks.connection.max_idle_time",60000));
        connector.setStatsOn(false);
        this.addConnector(connector);

        final File contextRoot = new File(Configurations.get("gridworks.webapp","webapp"));
        final String contextPath = Configurations.get("gridworks.context_path","/");

        File webXml = new File(contextRoot, "WEB-INF/web.xml");
        if (!webXml.isFile()) {
            Gridworks.warn("Warning: Failed to find web application. Could not find 'web.xml' at '" + webXml.getAbsolutePath() + "'");
            System.exit(-1);
        }

        Gridworks.info("Initializing context: '" + contextPath + "' from '" + contextRoot.getAbsolutePath() + "'");
        WebAppContext context = new WebAppContext(contextRoot.getAbsolutePath(), contextPath);
        //context.setCopyWebDir(false);
        //context.setDefaultsDescriptor(null);

        this.setHandler(context);
        this.setStopAtShutdown(true);
        this.setSendServerVersion(true);

        // Enable context autoreloading
        if (Configurations.getBoolean("gridworks.autoreloading",false)) {
            scanForUpdates(contextRoot, context);
        }
        
        this.start();
        this.join();
    }
    
    @Override
    protected void doStop() throws Exception {    
        try {
            // shutdown our scheduled tasks first, if any
            if (threadPool != null) threadPool.shutdown();
            
            // then let the parent stop
            super.doStop();
        } catch (InterruptedException e) {
            // ignore
        }
    }
        
    private void scanForUpdates(final File contextRoot, final WebAppContext context) {
        List<File> scanList = new ArrayList<File>();

        scanList.add(new File(contextRoot, "WEB-INF/web.xml"));
        findFiles(".class", new File(contextRoot, "WEB-INF/classes"), scanList);

        Gridworks.info("Starting autoreloading scanner... ");

        Scanner scanner = new Scanner();
        scanner.setScanInterval(Configurations.getInteger("gridworks.scanner.period",1));
        scanner.setScanDirs(scanList);
        scanner.setReportExistingFilesOnStartup(false);

        scanner.addListener(new Scanner.BulkListener() {
            @SuppressWarnings("unchecked")
            public void filesChanged(List changedFiles) {
                try {
                    Gridworks.info("Stopping context: " + contextRoot.getAbsolutePath());
                    context.stop();

                    Gridworks.info("Starting context: " + contextRoot.getAbsolutePath());
                    context.start();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        });

        scanner.start();
    }
    
    private void findFiles(final String extension, File baseDir, final Collection<File> found) {
        baseDir.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if (pathname.isDirectory()) {
                    findFiles(extension, pathname, found);
                } else if (pathname.getName().endsWith(extension)) {
                    found.add(pathname);
                }
                return false;
            }
        });
    }
    
}

/* -------------- Gridworks Client ----------------- */

class GridworksClient extends JFrame implements ActionListener {
    
    private static final long serialVersionUID = 7886547342175227132L;

    public static boolean MACOSX = (System.getProperty("os.name").toLowerCase().startsWith("mac os x"));
    
    private URI uri;
    
    @SuppressWarnings("unchecked")
    public void init(String host, int port) throws Exception {

        uri = new URI("http://" + host + ":" + port + "/");

        if (MACOSX) {

            // for more info on the code found here that is macosx-specific see:
            //  http://developer.apple.com/mac/library/documentation/Java/Conceptual/Java14Development/07-NativePlatformIntegration/NativePlatformIntegration.html
            //  http://developer.apple.com/mac/library/releasenotes/CrossPlatform/JavaSnowLeopardUpdate1LeopardUpdate6RN/NewandNoteworthy/NewandNoteworthy.html

            JMenuBar mb = new JMenuBar(); 
            JMenu m = new JMenu("Open");
            JMenuItem mi = new JMenuItem("Open New Gridworks Window...");
            mi.addActionListener(this);
            m.add(mi);
            mb.add(m);

            Class applicationClass = Class.forName("com.apple.eawt.Application"); 
            Object macOSXApplication = applicationClass.getConstructor((Class[]) null).newInstance((Object[]) null);
            Method setDefaultMenuBar = applicationClass.getDeclaredMethod("setDefaultMenuBar", new Class[] { JMenuBar.class });
            setDefaultMenuBar.invoke(macOSXApplication, new Object[] { mb });
           
            // FIXME(SM): this part below doesn't seem to work, I get a NPE but I have *no* idea why, suggestions?
            
//            PopupMenu dockMenu = new PopupMenu("dock");
//            MenuItem mmi = new MenuItem("Open new Gridworks Window...");
//            mmi.addActionListener(this);
//            dockMenu.add(mmi);
//            this.add(dockMenu);
//
//            Method setDockMenu = applicationClass.getDeclaredMethod("setDockMenu", new Class[] { PopupMenu.class });
//            setDockMenu.invoke(macOSXApplication, new Object[] { dockMenu });
        }
        
        openBrowser();
    }
    
    public void actionPerformed(ActionEvent e) { 
      String item = e.getActionCommand(); 
      if (item.startsWith("Open")) {
          openBrowser();
      }
    } 
    
    private void openBrowser() {
        try {
            Desktop.getDesktop().browse(uri); 
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

class ShutdownSignalHandler extends SignalHandler {
    
    private Server _server;

    public ShutdownSignalHandler(String sigName, Server server) {
        super(sigName);
        this._server = server;
    }

    public boolean handle(String signame) {

        //System.err.println("Received Signal: " + signame);
        
        // Tell the server we want to try and shutdown gracefully
        // this means that the server will stop accepting new connections
        // right away but it will continue to process the ones that
        // are in execution for the given timeout before attempting to stop
        // NOTE: this is *not* a blocking method, it just sets a parameter
        //       that _server.stop() will rely on
        _server.setGracefulShutdown(3000);

        try {
            _server.stop();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        return true;
    }
}
    