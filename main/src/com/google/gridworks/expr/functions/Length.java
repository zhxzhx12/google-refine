package com.google.gridworks.expr.functions;

import java.util.Collection;
import java.util.Properties;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONWriter;

import com.google.gridworks.expr.EvalError;
import com.google.gridworks.expr.HasFieldsList;
import com.google.gridworks.gel.ControlFunctionRegistry;
import com.google.gridworks.gel.Function;

public class Length implements Function {

    public Object call(Properties bindings, Object[] args) {
        if (args.length == 1) {
            Object v = args[0];
            
            if (v != null) {
                if (v.getClass().isArray()) {
                    Object[] a = (Object[]) v;
                    return a.length;
                } else if (v instanceof Collection<?>) {
                    return ((Collection<?>) v).size();
                } else if (v instanceof HasFieldsList) {
                    return ((HasFieldsList) v).length();
                } else if (v instanceof JSONArray) {
                    return ((JSONArray) v).length();
                } else {
                    String s = (v instanceof String ? (String) v : v.toString());
                    return s.length();
                }
            }
        }
        return new EvalError(ControlFunctionRegistry.getFunctionName(this) + " expects an array or a string");
    }

    public void write(JSONWriter writer, Properties options)
        throws JSONException {
    
        writer.object();
        writer.key("description"); writer.value("Returns the length of o");
        writer.key("params"); writer.value("array or string o");
        writer.key("returns"); writer.value("number");
        writer.endObject();
    }
}
