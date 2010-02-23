package com.metaweb.gridworks.expr.functions;

import java.util.Arrays;
import java.util.Properties;

import org.json.JSONException;
import org.json.JSONWriter;

import com.metaweb.gridworks.expr.Function;

public class Sort implements Function {

	public Object call(Properties bindings, Object[] args) {
		if (args.length == 1) {
			Object v = args[0];
			
			
			if (v != null && v.getClass().isArray()) {
				Object[] a = (Object[]) v;
				Object[] r = a.clone();
				
				Arrays.sort(r, 0, r.length);
				
				return r;
			}
		}
		return null;
	}

	public void write(JSONWriter writer, Properties options)
		throws JSONException {
	
		writer.object();
		writer.key("description"); writer.value("Sorts array a");
		writer.key("params"); writer.value("array a");
		writer.key("returns"); writer.value("array");
		writer.endObject();
	}
}
