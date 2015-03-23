package com.chteuchteu.munincrawler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class Index {
	private JsonArray index;
	
	private String currentCategory;
	private String currentNode;
	
	public Index() {
		this.index = new JsonArray();
	}
	
	public void addCategory(String categoryName) {
		this.currentCategory = categoryName;
	}
	
	public void addNode(String nodeName) {
		this.currentNode = nodeName;
	}
	
	public void addPlugin(String pluginName, String fileName) {
		JsonObject plugin = new JsonObject();
		plugin.addProperty("name", pluginName);
		plugin.addProperty("category", currentCategory);
		plugin.addProperty("node", currentNode);
		plugin.addProperty("file", fileName);
		index.add(plugin);
	}
}
