package com.metaweb.gridworks.broker;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;
import javax.jdo.Transaction;
import javax.jdo.annotations.IdGeneratorStrategy;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.PrimaryKey;
import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.appengine.api.datastore.Text;
import com.metaweb.gridworks.appengine.AppEngineClientConnectionManager;

public class AppEngineGridworksBroker extends GridworksBroker {
                
    protected static final Logger logger = LoggerFactory.getLogger("gridworks.broker.appengine");
    
    PersistenceManagerFactory pmfInstance;
    
    @Override
    public void init(ServletConfig config) throws Exception {
        super.init(config);
        
        pmfInstance = JDOHelper.getPersistenceManagerFactory("transactional");
    }
    
    @Override
    public void destroy() throws Exception {
    }

    // ---------------------------------------------------------------------------------

    protected HttpClient getHttpClient() {
        ClientConnectionManager cm = new AppEngineClientConnectionManager();
        return new DefaultHttpClient(cm, null);
    }
    
    // ---------------------------------------------------------------------------------
    
    protected void getLock(HttpServletResponse response, String pid) throws Exception {
        PersistenceManager pm = pmfInstance.getPersistenceManager();
        
        try {
            respond(response, lockToJSON(getLock(pm,pid)));
        } finally {
            pm.close();
        }
    }

    protected void obtainLock(HttpServletResponse response, String pid, String uid) throws Exception {
        PersistenceManager pm = pmfInstance.getPersistenceManager();
        
        try {
            Lock lock = getLock(pm, pid);
            if (lock == null) {
                Transaction tx = pm.currentTransaction();
                
                try {
                    tx.begin();
                    lock = new Lock(Long.toHexString(tx.hashCode()), pid, uid);
                    pm.makePersistent(lock);
                    tx.commit();
                } finally {
                    if (tx.isActive()) {
                        tx.rollback();
                    }
                }
            }
            
            respond(response, lockToJSON(lock));
            
        } finally {
            pm.close();
        }
    }
    
    protected void releaseLock(HttpServletResponse response, String pid, String uid, String lid) throws Exception {

        PersistenceManager pm = pmfInstance.getPersistenceManager();
        
        try {
            Lock lock = getLock(pm, pid);
            if (lock != null) {
                if (!lock.id.equals(lid)) {
                    throw new RuntimeException("Lock id doesn't match, can't release the lock");
                }
                if (!lock.uid.equals(uid)) {
                    throw new RuntimeException("User id doesn't match the lock owner, can't release the lock");
                }

                Transaction tx = pm.currentTransaction();
                
                try {
                    tx.begin();
                    pm.deletePersistent(lock);
                    tx.commit();
                } finally {
                    if (tx.isActive()) {
                        tx.rollback();
                    }
                }
            }
            
            respond(response, OK);
            
        } finally {
            pm.close();
        }
    }
    
    // ----------------------------------------------------------------------------------------------------
    
    protected void startProject(HttpServletResponse response, String pid, String uid, String lid, String data) throws Exception {
        PersistenceManager pm = pmfInstance.getPersistenceManager();
        
        try {
            checkLock(pm, pid, uid, lid);
            
            Project project = getProject(pm, pid);
            
            if (project != null) {
                throw new RuntimeException("Project '" + pid + "' already exists");
            }
            
            Transaction tx = pm.currentTransaction();
            
            try {
                tx.begin();
                project = new Project(pid, data);
                pm.makePersistent(project);
                tx.commit();
            } finally {
                if (tx.isActive()) {
                    tx.rollback();
                }
            }
            
            respond(response, OK);
        } finally {
            pm.close();
        }
    }

    protected void addTransformations(HttpServletResponse response, String pid, String uid, String lid, List<String> transformations) throws Exception {
        PersistenceManager pm = pmfInstance.getPersistenceManager();
        
        try {
            checkLock(pm, pid, uid, lid);

            Project project = getProject(pm, pid);
            
            if (project == null) {
                throw new RuntimeException("Project '" + pid + "' not found");
            }

            Transaction tx = pm.currentTransaction();

            try {
                for (String s : transformations) {
                    project.transformations.add(new Text(s));
                }
                tx.commit();
            } finally {
                if (tx.isActive()) {
                    tx.rollback();
                }
            }
            
            respond(response, OK);
        } finally {
            pm.close();
        }
    }

    // ---------------------------------------------------------------------------------
    
    protected void getProject(HttpServletResponse response, String pid) throws Exception {
        PersistenceManager pm = pmfInstance.getPersistenceManager();
        
        try {
            Project project = getProject(pm, pid);

            Writer w = response.getWriter();
            JSONWriter writer = new JSONWriter(w);
            writer.object();
                writer.key("data"); writer.value(project.data.toString());
                writer.key("transformations"); 
                writer.array();
                    for (Text s : project.transformations) {
                        writer.value(s.toString());
                    }
                writer.endArray();
            writer.endObject();
            w.flush();
            w.close();
        } finally {
            pm.close();
        }
    }
    
    protected void getHistory(HttpServletResponse response, String pid, int tindex) throws Exception {
        PersistenceManager pm = pmfInstance.getPersistenceManager();
        
        try {
            Project project = getProject(pm, pid);

            Writer w = response.getWriter();
            JSONWriter writer = new JSONWriter(w);
            writer.object();
            writer.key("transformations"); 
            writer.array();
                int size = project.transformations.size();
                for (int i = tindex; i < size; i++) {
                    writer.value(project.transformations.get(i).toString());
                }
            writer.endArray();
            writer.endObject();
            w.flush();
            w.close();
        } finally {
            pm.close();
        }
    }

    // ---------------------------------------------------------------------------------
    
    Project getProject(PersistenceManager pm, String pid) {
        Project project = pm.getObjectById(Project.class, pid);
        if (project == null) {
            throw new RuntimeException("Project '" + pid + "' is not managed by this broker");
        }
        return project;
    }
        
    @PersistenceCapable    
    static class Project {
        
        @PrimaryKey
        @Persistent(valueStrategy = IdGeneratorStrategy.IDENTITY)
        String pid;

        @Persistent
        List<Text> transformations = new ArrayList<Text>(); 

        @Persistent
        Text data;

        Project(String pid, String data) {
            this.pid = pid;
            this.data = new Text(data);
        }
    }

    // ---------------------------------------------------------------------------------
    
    Lock getLock(PersistenceManager pm, String pid) {
        return pm.getObjectById(Lock.class, pid);
    }

    void checkLock(PersistenceManager pm, String pid, String uid, String lid) {
        Lock lock = getLock(pm, pid);
    
        if (lock == null) {
            throw new RuntimeException("No lock was found with the given Lock id '" + lid + "', you have to have a valid lock on a project in order to start it");
        }
        
        if (!lock.pid.equals(pid)) {
            throw new RuntimeException("Lock '" + lid + "' is for another project: " + pid);
        }
        
        if (!lock.uid.equals(uid)) {
            throw new RuntimeException("Lock '" + lid + "' is owned by another user: " + uid);
        }
    }
    
    JSONObject lockToJSON(Lock lock) throws JSONException {
        JSONObject o = new JSONObject();
        if (lock != null) {
            o.put("lock_id", lock.id);
            o.put("project_id", lock.pid);
            o.put("user_id", lock.uid);
        }
        return o;
    }
    
    @PersistenceCapable    
    static class Lock {

        @Persistent
        String id;
        
        @PrimaryKey
        @Persistent(valueStrategy = IdGeneratorStrategy.IDENTITY)
        String pid;
        
        @Persistent
        String uid;
        
        Lock(String id, String pid, String uid) {
            this.id = id;
            this.pid = pid;
            this.uid = uid;
        }
    }
    
}
