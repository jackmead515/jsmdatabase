import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import JSMParser;

/**
 * Manager for the non-redundant, low resource, high throughput file database.
 * What does that mean?
 * 
 * <b>non-redundant</b> - No file is ever written twice. Once it's in the database, it will not be overridden.
 * <br />
 * <b>low resource</b> - If your hardware is on a budget, you can throttle the speed of the database down as
 * to not consume many resources.
 * <br />
 * <b>high throughput</b> - This database can and will except high volumes of traffic since it is low resource.
 * It might just take a bit longer for all the files to be written to the databases.
 * <br/>
 * <b>file database</b> - This database stores whatever files you want without redundancy.
 * <br />
 * @author Jack Mead
 */
public class JSMDBManager extends Thread {
	
	private JSMParser parser;
	
	/**
	 * List of databases that are being used
	 */
	private volatile LinkedList<JSMDatabase> databases;
	
	/**
	 * List of directories that are avaliable for use by
	 * the databases. List amount is determined by the maxDBs
	 * amount. The directories will be generated within the
	 * rootDBDir and will have the syntax of 'db#/', where 
	 * '#' represents an integer value starting from 1.
	 */
	private volatile LinkedList<String> mountDirectories;
	
	/**
	 * Shell script used to check if a device is mounted
	 */
	private final String checkMountString = "if grep -qs $1 /proc/mounts; then\n" + 
			"    echo \"true\"\n" + 
			"else\n" + 
			"    echo \"false\"\n" + 
			"fi";
	
	/**
	 * Shell script used to list the usb storage devices connected to the machine.
	 */
	private final String listDevicesString = "for device in /sys/block/*\n" + 
			"do\n" + 
			"    if udevadm info --query=property --path=$device | grep -q ^ID_BUS=usb\n" + 
			"    then\n" + 
			"	echo $device\n" + 
			"    fi\n" + 
			"done";
	
	/**
	 * Shell script used to print out the different mount paths
	 * for the connected usb storage devices. 
	 */
	private final String mountPathsString = "if [ -z \"$1\" ]; then\n" + 
			"   echo \"null\"\n" + 
			"else\n" + 
			"    grep \"^$1\" /proc/mounts | cut -d ' ' -f 2\n" + 
			"fi";
	
	/**
	 * Directory to store the shell files above
	 */
	private static String linuxScriptsDir;
	
	/**
	 * Directory to log information about the databases
	 */
	private static String infoFileDir;
	
	/**
	 * Root directory of the database from which 
	 * the default and database directories will be created
	 */
	private static String rootDBDir;
	
	/**
	 * Total amount of databases allowed to connect to the manager.
	 * This should be set to the total amount of USB devices you want
	 * connected to your machine
	 */
	private int maxDBs;
	
	/**
	 * HashMap containing the file names residing 
	 * in the default directory
	 */
	private HashMap<String, String> filesInDefault;
	
	private int maxDatabaseWriters;
	
	private int databaseWriteSpeed;
	
	/**
	 * Constructor for the manager. After calling a creating a new manager,
	 * One should call setMaxDatabases(), setRootDBDirectory(), setInfoFileDirectory(),
	 * setLinuxScriptsDirectory(), setMaxDatabaseWriters(), and setDatabaseWriteSpeed(). 
	 * Finally, call the init() method before attempting to write to the database.
	 */
	public JSMDBManager() {
		databases = new LinkedList<JSMDatabase>();
		filesInDefault = new HashMap<String, String>();
		mountDirectories = new LinkedList<String>();
		infoFileDir = null;
		rootDBDir = null;
		linuxScriptsDir = null;
		maxDBs = 0;
		maxDatabaseWriters = 0;
		databaseWriteSpeed = 0;
	}
	
	public void setMaxDatabaseWriters(int amount) {
		this.maxDatabaseWriters = amount;
	}
	
	public void setDatabaseWriteSpeed(int speedms) {
		this.databaseWriteSpeed = speedms;
	}
	
	/**
	 * Retrieves full 777 permissions for use in downloading and manipulating files.
	 * @return 777 file permissions.
	 */
	public static FileAttribute<Set<PosixFilePermission>> getFullPermissions() {
		return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));
	}
	
	/**
	 * Mounts a valid drive to the indicated path.
	 * @param devName - syntax of /dev/sd*# where the '*' represents a lower case letter, and the '#' represents a whole integer value.
	 * @param path - directory to mount drive too, starting with '/' and ending with '/'.
	 * @return boolean indicating success of mount.
	 */
	public static synchronized boolean mountHardDrive(String devName, String path) {
		ArrayList<String> errorStream = new ArrayList<String>();
		try {
			Process p1 = Runtime.getRuntime().exec("sudo mount " + devName + " " + path);
			Scanner s = new Scanner(p1.getErrorStream());
			while(s.hasNextLine()) { 
				errorStream.add(s.nextLine()); 
			}
			s.close();
			p1.waitFor();
			
			boolean alreadyMounted = false;
			for(String line : errorStream) {
				if(line.matches("(.*)already mounted on " + path.substring(0, path.length()-2) + "(.*)")) {
					alreadyMounted = true;
					break;
				}
			}

			if(errorStream.isEmpty() || alreadyMounted){ 
				return true; 
			}	
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		return false;
	}
	
	/**
	 * Retrieves info about all usb drives connected
	 * @return LinkedList<String[]> of the external drives connected to 
	 * the usb ports and details about them.
	 * [0] - /dev/ devices url <br />
	 * [1] - Total size of usb drive <br /> 
	 * [2] - Total space used on drive <br />
	 * [3] - Total space avaliable on drive <br />
	 * [4] - Total space used in percentage <br />
	 * [5] - Directory in which device is mounted <br />
	 */
	public static synchronized LinkedList<String[]> listUSBDrives() {
		LinkedList<String> drives = new LinkedList<String>();
		LinkedList<String[]> info = new LinkedList<String[]>();
		try {
			Process p = Runtime.getRuntime().exec(linuxScriptsDir + "listDevices.sh");
			Scanner scan = new Scanner(p.getInputStream());
			while(scan.hasNextLine()) {
				String line = scan.nextLine();
				String dev = line.substring(line.lastIndexOf("/")+1);
				if(dev.startsWith("sd")) {
					drives.add(dev);
				}
			}
			scan.close();
			p.waitFor();
			
			p = Runtime.getRuntime().exec("sudo df -h");
			scan = new Scanner(p.getInputStream());
			while(scan.hasNextLine()) {
				String line = scan.nextLine();
				for(int i = 0; i < drives.size(); i++) {
					if(line.startsWith("/dev/" + drives.get(i))) {
						info.add(line.replaceAll("\\s+", " ").split(" "));
					}
				}
			}
			scan.close();
			p.waitFor();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return info;
	}
	
	/**
	 * Retrieve info relevant to the dev drive passed
	 * @return String[] of the information of the dev device passed
	 * [0] - /dev/ devices url <br />
	 * [1] - Total size of usb drive <br />
	 * [2] - Total space used on drive <br />
	 * [3] - Total space avaliable on drive <br />
	 * [4] - Total space used in percentage <br />
	 * [5] - Directory in which device is mounted <br />
	 */
	public static synchronized String[] listUSBDriveInfo(String devName) {
		String[] info = null;
		try {
			Process p = Runtime.getRuntime().exec("sudo df -h");
			Scanner scan = new Scanner(p.getInputStream());
			while(scan.hasNextLine()) {
				String line = scan.nextLine();
				if(line.startsWith(devName)) {
					info = line.replaceAll("\\s+", " ").split(" ");
					break;
				}
			}
			scan.close();
			p.waitFor();
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		return info;
	}
	
	/**
	 * Retrieves the directory in which the drive is mounted too.
	 * @param devName - syntax of /dev/sd*# where the '*' represents a lower case letter, and the '#' represents a whole integer value.
	 * @return List of mount paths for which the drive is mounted.
	 */
	public static synchronized LinkedList<String> getMountPaths(String devName) {
		LinkedList<String> info = new LinkedList<String>();
		try {
			Process p = Runtime.getRuntime().exec("sudo " + linuxScriptsDir + "mountPaths.sh " + devName);
			Scanner scan = new Scanner(p.getInputStream());
			while(scan.hasNextLine()) {
				String line = scan.nextLine();
				if(line.startsWith("/")) { info.add(line.trim().concat("/")); }
			}
			scan.close();
			p.waitFor();
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		return info;
	}
	
	/**
	 * Uses shell script "checkMount.sh" to check a dev 
	 * device if it's mounted.
	 * @param device 
	 * @return boolean indicated if device is mounted.
	 */
	public synchronized static boolean isMounted(String device) {
		try {
			Process p = Runtime.getRuntime().exec("sudo " + linuxScriptsDir + "checkMount.sh " + device);
			Scanner scan = new Scanner(p.getInputStream());
			
			boolean mounted = false;
			
			while(scan.hasNextLine()){
				String line = scan.nextLine();
				if(line.equals("true")){
					mounted = true;
				}
			}
			scan.close();
			p.waitFor();
			
			return mounted;
		} catch (Exception e){
			return false;
		}
	}
	
	/**
	 * Returns a String indicating the percentage of storage left in the directory of a mounted device. 
	 * Directory starts with "/" and ends with "/".
	 * return 0 if any exception is thrown or if isMounted() returns false.
	 * @return String indicating the percentage of storage left
	 */
	public synchronized static int storageLeftOnMountedDevice(String dir, String name) {
		try {	
			boolean mounted = JSMDBManager.isMounted(name);
			
			if(mounted){
				try {
					long usable = new File(dir).getUsableSpace();
					long total = new File(dir).getTotalSpace();
					
					double percent = ((double)usable / total) * 100;
					
					return (int)percent;
				} catch (Exception e) {
					return 0;
				}
			} else {
				return 0;
			}
		} catch(Exception e) {
			return 0;
		}
	}
	
	/**
	 * Call start() to begin the thread which will log the usb drive information 
	 * to usb_drive.info and unmount any usb drive that is no longer valid.
	 */
	public void run() {
		while(true) {
			try {
				Iterator<JSMDatabase> dbir = this.databases.iterator();
				while (dbir.hasNext()) {
					JSMDatabase db = dbir.next();
					if(!db.isValid()) {
						db.uninit();
						if(!mountDirectories.contains(db.getUSBDrivePath())) {
							mountDirectories.add(db.getUSBDrivePath());
						}
						dbir.remove();
					}
				}
				
				LinkedList<String[]> devices = JSMDBManager.listUSBDrives();
				for(String[] device : devices) {
					boolean usbInDB = false;
					for(JSMDatabase db : this.databases) {
						if(db.getUSBDriveName().equals(device[0])) {
							usbInDB = true; break;
						}
					}
					if(!usbInDB) {
						if(this.mountDirectories.size() > 0) {
							JSMDatabase db = new JSMDatabase(this.mountDirectories.getFirst(), device[0]);
							db.setFileWriteSpeed(this.databaseWriteSpeed);
							db.setMaxWriters(this.maxDatabaseWriters);
							if(db.init()) { 
								this.databases.add(db);
								this.mountDirectories.removeFirst();
							}
						} else {
							break;
						}
					}
				}
				
				parser.clear();
				int i = 1;
				for(JSMDatabase db : this.databases) {
					parser.set("USBName_" + i, db.getUSBDriveName());
					parser.set("USBPath_" + i, db.getUSBDrivePath());
					parser.set("TotalFiles_" + i, String.valueOf(db.getTotalFilesInDB()));
					parser.set("USBStorage_" + i,	String.valueOf(db.getStorageLeft()));
					i+=1;
				}
				parser.save();
				
				TimeUnit.SECONDS.sleep(10);				
			} catch(Exception e) { e.printStackTrace(); }
		}
	}
	
	/**
	 * Sets the total databases allowed in manager
	 * @param amount
	 */
	public void setMaxDatabases(int amount) {
		this.maxDBs = amount;
	}
	
	/**
	 * Sets the directory where the databases will be mounted.
	 * @param dir - should start with '/' and end with '/'
	 */
	public void setRootDBDirectory(String dir) {
		rootDBDir = dir;
	}
	
	/**
	 * Sets the directory in which drive information will be printed to 'usb_drive.info'.
	 * @param dir - should start with '/' and end with '/'
	 */
	public void setInfoFileDirectory(String dir) {
		infoFileDir = dir;
	}
	
	/**
	 * Sets the directory in which the helper linux script files will be created and stored.
	 * @param dir - should start with '/' and end with '/'
	 */
	public void setLinuxScriptsDirectory(String dir) {
		linuxScriptsDir = dir;
	}
	
	/**
	 * Initializes the databases and creates the directories needed.
	 * This will not overwrite any existing databases or files. Call this 
	 * method before attempting to write to the database.
	 */
	public void init() {
		
		//creating database directories
		for(int i = 1; i < (this.maxDBs+1); i++) {
			this.mountDirectories.add(rootDBDir + "db" + i + "/");
		}
		
		//creating default database directory
		if(!Files.exists(Paths.get(rootDBDir + "default/"))) {
			try {
				Files.createDirectory(Paths.get(rootDBDir + "default/"), JSMDBManager.getFullPermissions());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		//creating info file
		if(!Files.exists(Paths.get(infoFileDir + "usb_drives.info"))) {
			try {
				Files.createFile(Paths.get(infoFileDir + "usb_drives.info"), JSMDBManager.getFullPermissions());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		//creating check mount shell file
		if(!Files.exists(Paths.get(linuxScriptsDir + "checkMount.sh"))) {
			try {
				Files.createFile(Paths.get(linuxScriptsDir + "checkMount.sh"), JSMDBManager.getFullPermissions());
				FileOutputStream fos = new FileOutputStream(linuxScriptsDir + "checkMount.sh");
				fos.write(this.checkMountString.getBytes()); fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		//creating list usb devices shell file
		if(!Files.exists(Paths.get(linuxScriptsDir + "listDevices.sh"))) {
			try {
				Files.createFile(Paths.get(linuxScriptsDir + "listDevices.sh"), JSMDBManager.getFullPermissions());
				FileOutputStream fos = new FileOutputStream(linuxScriptsDir + "listDevices.sh");
				fos.write(this.listDevicesString.getBytes()); fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		//creating list mount paths shell file
		if(!Files.exists(Paths.get(linuxScriptsDir + "mountPaths.sh"))) {
			try {
				Files.createFile(Paths.get(linuxScriptsDir + "mountPaths.sh"), JSMDBManager.getFullPermissions());
				FileOutputStream fos = new FileOutputStream(linuxScriptsDir + "mountPaths.sh");
				fos.write(this.mountPathsString.getBytes()); fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		parser = new JSMParser(Paths.get(infoFileDir + "usb_drives.info"));
		LinkedList<String[]> devices = JSMDBManager.listUSBDrives();
		
		for(String[] device : devices) {
			if(this.mountDirectories.size() > 0) {
				JSMDatabase db = new JSMDatabase(this.mountDirectories.getFirst(), device[0]);
				db.setFileWriteSpeed(this.databaseWriteSpeed);
				db.setMaxWriters(this.maxDatabaseWriters);
				if(db.init()) { 
					this.databases.add(db);
					this.mountDirectories.removeFirst();
				}
			} else {
				break;
			}
		}
		
		File[] files = new File(rootDBDir + "default/").listFiles(File::isFile);
		for(int i = 0; i < files.length; i++) {
			filesInDefault.put(files[i].getName(), files[i].getName());
		}
	}
	
	/**
	 * Retrieves the database list
	 * @return
	 */
	public synchronized LinkedList<JSMDatabase> getDatabases() {
		return this.databases;
	}

	/**
	 * Queues all databases to finish writing files and unmount the drives.
	 * After calling this method to object will no longer be usuable and should
	 * be recreated. 
	 */
	public synchronized void uninit() {
		for(JSMDatabase db : this.databases) {
			db.uninit();
			mountDirectories.add(db.getUSBDrivePath());
		}
		this.databases.clear();
	}
	
	/**
	 * Gets the total amount of files in the default directory
	 * @return
	 */
	public synchronized int totalDefaultFiles() {
		return this.filesInDefault.size();
	}
	
	/**
	 * Retrieves the total amount of files in databases and
	 * in the default directory
	 * @return
	 */
	public synchronized int totalFiles() {
		int amount = 0;
		for(JSMDatabase db : this.databases) {
			amount += db.getTotalFilesInDB();
		}
		amount+=this.totalDefaultFiles();
		return amount;
	}
	
	/**
	 * Retrieves the usb drive names that are in use by this
	 * manager. Each item uses the syntax of /dev/sd*# where the 
	 * '*' represents a lower case letter, and the '#' represents
	 *  a whole integer value.
	 * @return list of drives currently being used in manager.
	 */
	public synchronized LinkedList<String> usbDriveNames() {
		LinkedList<String> hardDriveNames = new LinkedList<String>();
		for(JSMDatabase db : this.databases) {
			hardDriveNames.add(db.getUSBDriveName());
		}
		return hardDriveNames;
	}
	
	/**
	 * Retrieves the percentage of storage left for use in the databases.
	 * @return
	 */
	public synchronized int storageLeft() {
		double tp = this.getDatabases().size()*100;
		if(tp <= 0) { return 0; }
		
		double tsu = 0;
		for(JSMDatabase db : this.databases) {
			tsu += db.getStorageLeft();
		}
		
		return (int) ((tsu/tp)*100);
	}
	
	/**
	 * Tests whether or not a file is currently being written by a database.
	 * @param fileName - file name to test
	 * @return boolean for whether or not file is currently being written.
	 */
	public synchronized boolean isCurrentlyWriting(String fileName) {
		for(JSMDatabase db : this.databases) {
			if(db.isCurrentlyWriting(fileName)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Tests whether or not a file is written to the database
	 * @param fileName - name of file to check 
	 * @return boolean for whether or not the file has been written to a database
	 */
	public synchronized boolean isWrittenToDB(String fileName) {
		for(JSMDatabase db : this.databases) {
			if(db.isWrittenToDB(fileName)) {
				return true;
			}
		}
		if(this.filesInDefault.get(fileName) != null) {
			return true;
		}
		return false;
	}
	
	/**
	 * Adds a file name to the list of files that are in the default directory
	 * @param name - file name to put into the default list
	 */
	private void addToFilesInDefault(String name) {
		filesInDefault.put(name, name);
	}
	
	/**
	 * Selects a random database to write a file to. As long as 
	 * database is valid and it can accept another file to write to it,
	 * an index will be selected.
	 * @return index in the databases list to be a valid database. Will 
	 * return -1 if no database is available to write to.
	 */
	private synchronized int select(String name) {
		int selection = -1;
		for(int i = 0; i < this.databases.size(); i++) {
			JSMDatabase db = this.databases.get(i);
			if(db.canWriteFile() && db.isValid()) {
				selection = i;
				break;
			}
		}
		return selection;
	}
	
	/**
	 * Saves a file to a database or to the default directory if no database is available.
	 * To successfully write to the database, call the isCurrentlyWriting() and isWrittenToDB()
	 * methods to test whether or not to even write the file. If these methods are not called
	 * beforehand and the file was already queued in the databases, the file will not be overridden
	 * but may be copied in another database will cause unnecessary resources to be consumed. 
	 * @param data - raw data of file
	 * @param name - name of the file
	 * @param force - boolean for whether or not to save the file to the default directory
	 * if no database is available. 
	 * @return boolean indicating if a file was successfully queue to be written to a database or
	 * to the default directory.
	 */
	public synchronized boolean save(byte[] data, String name, boolean force) {
		int index = this.select(name);
		if(index != -1) {
			JSMDatabase db = this.databases.get(index);
			if(db.write(data, name)) {
				return true;
			} else {
				return false;
			}
		} else if(force) {
			
			try {
				if(!Files.exists(Paths.get(rootDBDir + "default/" + name))) {
					Files.createFile(Paths.get(rootDBDir + "default/" + name), JSMDBManager.getFullPermissions());
					
					FileOutputStream fos = new FileOutputStream(rootDBDir + "default/" + name);
					fos.write(data); fos.close();
					
					this.addToFilesInDefault(name);
					
					return true;
				}
				
				return false;
			} catch(Exception e) {
				e.printStackTrace();
				return false;
			}
		} else {
			return false;
		}
	}
}
