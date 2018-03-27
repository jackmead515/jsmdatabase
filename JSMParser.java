import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.Set;
/**
 * Wrapper class to load and save to a file the JSM syntax.
 * Syntax is as follows:
 * 
 * key: value
 * key1: value1
 * key2: value2
 * 
 * where key is the object reference and value is whatever you set it too.
 * key nor value can contain a colon. Proper augmented values should use
 * a dash or underscore symbol. Each key-value pair should be on a new line.
 * 
 * @author Jack S Mead
 */
public class JSMParser {
	
	private Path path;
	private HashMap<String, String> data;
	
	public JSMParser(Path path) {
		this.path = path;
		this.data = new HashMap<String, String>();
	}
	
	public JSMParser() {
		this.data = new HashMap<String, String>();
	}
	
	/**
	 * Loads data variables into memory from the file.
	 * Fails if file does not exist or an out of bounds
	 * exception occurs, in which case, the file is in
	 * an incorrect format.
	 * @return boolean indicating success of load.
	 */
	public synchronized boolean load() {
		try {
			Scanner scan = new Scanner(path.toFile());
			while(scan.hasNextLine()){
				String line = scan.nextLine();
				String key = line.substring(0, line.indexOf(":"));
				String value = line.substring(line.indexOf(":")+2);
				data.put(key, value);
			}
			scan.close();
			return true;
			
		} catch (FileNotFoundException e){ 
			return false;
		} catch (IndexOutOfBoundsException e1) {
			return false;
		}
	}
	
	public synchronized boolean load(ArrayList<String> lines) {
		try {
			for(String line : lines) {
				String key = line.substring(0, line.indexOf(":"));
				String value = line.substring(line.indexOf(":")+2);
				data.put(key, value);
			}
			return true;
		} catch (IndexOutOfBoundsException e1) {
			return false;
		}
	}
	
	public static synchronized HashMap<String, String> parse(ArrayList<String> lines) {
		HashMap<String, String> parseData = new HashMap<String, String>();
		try {
			for(String line : lines){
				String key = line.substring(0, line.indexOf(":"));
				String value = line.substring(line.indexOf(":")+2);
				parseData.put(key, value);
			}
			return parseData;
		} catch (IndexOutOfBoundsException e1) {
			return null;
		}
	}
	
	/**
	 * Retrieves an arraylist of string values in proper
	 * JSM format.
	 * @return an ArrayList<String> that is empty
	 * or filled with key-value pairs.
	 */
	public ArrayList<String> getKeyValueSet() {
		ArrayList<String> d = new ArrayList<String>();
		String[] keys = (String[]) data.keySet().toArray();
		
		for(String key : keys) { d.add(key + ": " + data.get(key)); }
		
		return d;
	}
	
	public HashMap<String, String> getHashMap() {
		return this.data;
	}
	
	/**
	 * Retrieves the value from the corresponding key passed.
	 * @param name - represents the key of the key-value pair
	 * @return String or null if mapping to key does not exist.
	 */
	public String get(String name) {
		return data.get(name);
	}
	
	/**
	 * Sets a key-value pair in the hashmap
	 * @param name - represents the key of the key-value pair
	 * @param value - represents the value of the key-value pair
	 */
	public void set(String name, String value) {
		data.put(name, value);
	}
	
	public void remove(String name) {
		data.remove(name);
	}
	
	public void clear() {
		data.clear();
		try {
			File file = new File(path.toString());
			PrintWriter print = new PrintWriter(file);
			print.flush(); print.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Saves the key-value pair to a file as 
	 * indicated by constructor.
	 * @return boolean indicating success of saving 
	 * the key-value set to the path.
	 */
	public synchronized boolean save() {
		Set<String> keys = data.keySet();
		
		try {
			File file = new File(path.toString());
			PrintWriter print = new PrintWriter(file);
			
			print.flush();
			
			for(String key : keys){
				String value = data.get(key);
				print.println(key + ": " + value);
			}
			
			print.close();
			
			return true;
		} catch (FileNotFoundException e) {
			return false;
		}
	}
	
	/**
	 * Saves the passed arraylist to a file as 
	 * indicated by constructor.
	 * @return boolean indicating success of saving 
	 * the arraylist to the path.
	 */
	public synchronized boolean save(ArrayList<String> lines) {
		try {
			File file = new File(path.toString());
			PrintWriter print = new PrintWriter(file);
			print.flush();
			for(int i = 0; i < lines.size(); i++){ print.println(lines.get(i)); }
			print.close();
			
			return true;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			
			return false;
		}
	}
	
	/**
	 * Saves the passed linkedlist to a file as 
	 * indicated by constructor.
	 * @return boolean indicating success of saving 
	 * the linkedlist to the path.
	 */
	public synchronized boolean save(LinkedList<String> lines) {
		try {
			File file = new File(path.toString());
			PrintWriter print = new PrintWriter(file);
			print.flush();
			for(int i = 0; i < lines.size(); i++){ print.println(lines.get(i)); }
			print.close();
			
			return true;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			
			return false;
		}
	}
	
	public synchronized boolean append(String name, String value) {
		FileWriter fw = null;
		try {
			fw = new FileWriter(path.toString(), true);
			fw.write("\n" + name + ": " + value + "\n");
			fw.close();
			return true;
		} catch(IOException e){
			return false;
		}
	}

	public synchronized boolean append(ArrayList<String> lines) {
		FileWriter fw = null;
		try{
			fw = new FileWriter(path.toString(), true);
			fw.write("\n" + lines.get(0) + "\n");
			for(int i = 1; i < lines.size(); i++) { 
				fw.write(lines.get(i) + "\n"); 
			}
			fw.close();
			return true;
		} catch(IOException e){
			return false;
		}
	}
	
	
	
	
	
	
	
	

}
