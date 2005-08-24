package org.osjava.sj.loader;

import java.util.*;
import java.io.*;
import javax.naming.*;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import org.osjava.sj.loader.convert.ConvertRegistry;
import org.osjava.sj.loader.convert.Converter;

import org.osjava.sj.loader.util.*;

/**
 * Loads a .properties file into a JNDI server.
 */
public class JndiLoader {

    // separator, or just put them in as contexts?
    public static final String SIMPLE_DELIMITER = "org.osjava.sj.delimiter";

    // share the same InitialContext
    public static final String SIMPLE_SHARED = "org.osjava.sj.shared";

    // char(s) to replace : with on the filesystem in filenames
    public static final String SIMPLE_COLON_REPLACE = "org.osjava.sj.colon.replace";

    private static ConvertRegistry convertRegistry = new ConvertRegistry();

    private Hashtable table = new Hashtable();

    public JndiLoader() {
        this.table.put(SIMPLE_DELIMITER, "/");
    }
    
    public JndiLoader(Hashtable env) {
        if(!env.containsKey(SIMPLE_DELIMITER)) {
            throw new IllegalArgumentException("The property "+SIMPLE_DELIMITER+" is mandatory. ");
        }
        

        this.table.put(SIMPLE_DELIMITER, env.get(SIMPLE_DELIMITER));

        if(env.containsKey(SIMPLE_COLON_REPLACE)) {
            this.table.put(SIMPLE_COLON_REPLACE, env.get(SIMPLE_COLON_REPLACE));
        }

    }
    
    public void putParameter(String key, String value) {
        table.put(key, value);
    }

    public String getParameter(String key) {
        return (String) table.get(key);
    }

    /**
     * Loads all .properties files in a directory into a context
     */
    public void loadDirectory(File directory, Context ctxt) throws NamingException, IOException {
// System.err.println("Loading directory. ");

        if( !directory.isDirectory() ) {
            throw new IllegalArgumentException("java.io.File parameter must be a directory. ["+directory+"]");
        }

        File[] files = directory.listFiles();
        if(files == null) {
// System.err.println("Null files. ");
            return;
        }

        for(int i=0; i<files.length; i++) {
            File file = files[i];
            String name = file.getName();

            if(name.indexOf(":") != -1) {
                if(this.table.containsKey(SIMPLE_COLON_REPLACE)) {
                    name = Utils.replace( name, (String) this.table.get(SIMPLE_COLON_REPLACE), ":" );
                }
            }
// System.err.println("Consider: "+name);
            // TODO: Replace hack with a FilenameFilter

            if( file.isDirectory() ) {
                // HACK: Hack to stop it looking in .svn or CVS
                if(name.equals(".svn") || name.equals("CVS")) {
                    continue;
                }

// System.err.println("Is directory. Creating subcontext: "+name);
                Context tmpCtxt = ctxt.createSubcontext( name );
                loadDirectory(file, tmpCtxt);
            } else {
                // TODO: Make this a plugin system
                String[] extensions = new String[] { ".properties", ".ini", ".xml" };
                for(int j=0; j<extensions.length; j++) {
                    String extension = extensions[j];
                    if( file.getName().endsWith(extension) ) {
// System.err.println("Is "+extension+" file. "+name);
                        Context tmpCtxt = ctxt;
                        if(!file.getName().equals("default"+extension)) {
                            name = name.substring(0, name.length() - extension.length());
// System.err.println("Not default, so creating subcontext: "+name);
                            tmpCtxt = ctxt.createSubcontext( name );
                        }
                        load( loadFile(file), tmpCtxt );
                    }
                }
            }
        }

    }

    private Properties loadFile(File file) throws IOException {
        AbstractProperties p = null;

        if(file.getName().endsWith(".xml")) {
            p = new XmlProperties();
        } else
        if(file.getName().endsWith(".ini")) {
            p = new IniProperties();
        } else {
            p = new CustomProperties();
        }

        p.setDelimiter( (String) this.table.get(SIMPLE_DELIMITER) );

        FileInputStream fin = null;
        try {
            fin = new FileInputStream(file);
            p.load(fin);
            return p;
        } finally {
            if(fin != null) fin.close();
        }
    }


    /**
     * Loads a properties object into a context.
     */
    public void load(Properties properties, Context ctxt) throws NamingException {
// System.err.println("Loading Properties");

        String delimiter = (String) this.table.get(SIMPLE_DELIMITER);
        String typePostfix = delimiter + "type";

        // NOTE: "type" effectively turns on pseudo-nodes; if it 
        //       isn't there then other pseudo-nodes will result 
        //       in re-bind errors

        // scan for pseudo-nodes, aka "type":   foo.type
        // store in a temporary type table:    "foo", new Properties() with type="value"
        Map typeMap = new HashMap();
        Iterator iterator = properties.keySet().iterator();
        while(iterator.hasNext()) {
            String key = (String) iterator.next();

            if(key.endsWith( typePostfix )) {
// System.err.println("TYPE: "+key);
                Properties tmp = new Properties();
                tmp.put( "type", properties.get(key) );
                typeMap.put( key.substring(0, key.length() - typePostfix.length()), tmp );
            }

        }

// if it matches a type root, then it should be added to the properties
// if not, then it should be placed in the context
// for each type properties
// call convert: pass a Properties in that contains everything starting with foo, but without the foo
// put objects in context

        iterator = properties.keySet().iterator();
        while(iterator.hasNext()) {
            String key = (String) iterator.next();
            Object value = properties.get(key);

            if(key.endsWith( typePostfix )) {
                continue;
            }

            if(typeMap.containsKey(key)) {
// System.err.println("Typed: "+key);
                ( (Properties) typeMap.get(key) ).put("", value);
                continue;
            }

            if(key.indexOf(delimiter) != -1) {
                String pathText = removeLastElement( key, delimiter );
                String nodeText = getLastElement( key, delimiter );

                if(typeMap.containsKey(pathText)) {
// System.err.println("Sibling: "+key);
                    ( (Properties) typeMap.get(pathText) ).put(nodeText, value);
                    continue;
                }
            }

// System.err.println("Putting: "+key);
            jndiPut( ctxt, key, properties.get(key) );
        }

        Iterator typeIterator = typeMap.keySet().iterator();
        while(typeIterator.hasNext()) {
            String typeKey = (String) typeIterator.next();
            Properties typeProperties = (Properties) typeMap.get(typeKey);

            Object value = convert(typeProperties);
// System.err.println("Putting typed: "+typeKey);
            jndiPut( ctxt, typeKey, value );
        }

    }

    private void jndiPut(Context ctxt, String key, Object value) throws NamingException {
        // here we need to break by the specified delimiter

        // can't use String.split as the regexp will clash with the types of chars 
        // used in the delimiters. Could use Commons Lang. Quick hack instead.
//        String[] path = key.split( (String) this.table.get(SIMPLE_DELIMITER) );
        String[] path = Utils.split( key, (String) this.table.get(SIMPLE_DELIMITER) );

// System.err.println("LN: "+path.length);
        int lastIndex = path.length - 1;


        Context tmpCtxt = ctxt;

        for(int i=0; i < lastIndex; i++) {
            Object obj = tmpCtxt.lookup(path[i]);
            if(obj == null) {
// System.err.println("Creating subcontext: " + path[i] + " for " + key);
                tmpCtxt = tmpCtxt.createSubcontext(path[i]);
            } else
            if(obj instanceof Context) {
// System.err.println("Using subcontext: "+obj + " for " + key);
                tmpCtxt = (Context) obj;
            } else {
                throw new RuntimeException("Illegal node/branch clash. At branch value '"+path[i]+"' an Object was found: " +obj);
            }
        }
        
        Object obj = tmpCtxt.lookup(path[lastIndex]);
        if(obj == null) {
// System.err.println("Binding: "+path[lastIndex]+" on "+key);
            tmpCtxt.bind( path[lastIndex], value );
        } else {
// System.err.println("Rebinding: "+path[lastIndex]+" on "+key);
            tmpCtxt.rebind( path[lastIndex], value );
        }
    }

    private static Object convert(Properties properties) {
        String type = properties.getProperty("type");
        // TODO: handle a plugin type system
        
        String converterClassName = properties.getProperty("converter");
        if(converterClassName != null) {
            try {
                Class converterClass = Class.forName( converterClassName );
                Converter converter = (Converter) converterClass.newInstance();
                return converter.convert(properties, type);
            } catch(ClassNotFoundException cnfe) {
                throw new RuntimeException("Unable to find class: "+converterClassName, cnfe);
            } catch(IllegalAccessException ie) {
                throw new RuntimeException("Unable to access class: "+type, ie);
            } catch(InstantiationException ie) {
                throw new RuntimeException("Unable to create Converter " + type + " via empty constructor. ", ie);
            }
        }

        // TODO: Support a way to set the default converters in the jndi.properties 
        //       and in the API itself
        Converter converter = convertRegistry.getConverter(type);
        if(converter != null) {
            return converter.convert(properties, type);
        }

        return properties.get("");

    }

    // String methods to make the using code more readable
    private static String getLastElement( String str, String delimiter ) {
        int idx = str.lastIndexOf(delimiter);
        return str.substring(idx + 1);
    }
    private static String removeLastElement( String str, String delimiter ) {
        int idx = str.lastIndexOf(delimiter);
        return str.substring(0, idx);
    }

}
