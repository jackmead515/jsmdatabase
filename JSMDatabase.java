import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class JSMDatabase {
	
	private String usbDrivePath;
	private String usbDriveName;
	private int maxWriters;
	private int folderIndex;
	private int rootFiles;
	private int fileWriteSpeed;
	private ThreadPoolExecutor reducingRoot;
	private ThreadPoolExecutor fileWriters;
	private HashMap<String, String> filesInDB;
	private HashMap<String, String> currentlyWriting;
	private boolean isValid;
	
	/**
	 * Constructs a new database. Call init method directly after.
	 * @param usbDrivePath - Path to mount the root of the database
	 * @param usbDriveName - dev device name for usb device
	 */
	public JSMDatabase(String usbDrivePath, String usbDriveName) {
		filesInDB = new HashMap<String, String>();
		currentlyWriting = new HashMap<String, String>();
		this.usbDrivePath = usbDrivePath;
		this.usbDriveName = usbDriveName;
		this.fileWriteSpeed = 100;
		this.folderIndex = -1;
		this.rootFiles = 0;
		this.maxWriters = 4;
		this.reducingRoot = (ThreadPoolExecutor) Executors.newCachedThreadPool();
		this.fileWriters = (ThreadPoolExecutor) Executors.newCachedThreadPool();
		this.isValid = false;
	}
	
	/**
	 * Initializes the database by creating the root directory for it and
	 * mounting the hard drive too it. If mount is successful, the folder index
	 * is initialized and the total files within the database are counted.
	 * @return boolean indicating success of initializing this database
	 */
	public synchronized boolean init() {
		if(!Files.exists(Paths.get(this.usbDrivePath))) {
			try {
				Files.createDirectory(Paths.get(this.usbDrivePath), JSMDBManager.getFullPermissions());
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
		}
		
		if(JSMDBManager.mountHardDrive(this.usbDriveName, this.usbDrivePath)) {
			this.folderIndex = this.getFolderIndex();
			this.getFilesInDrive();
			this.isValid = true;
			return true;
		}
		
		return false;
	}
	
	/**
	 * Waits until reducing root process is completed and
	 * until all files have been written to database and then
	 * proceeds to unmount the drive. Database should be deleted
	 * and should then be recreated.
	 */
	public synchronized void uninit() {
		while(true) {
			if(this.isReducingRoot() || this.isWritingFiles()) {
				try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException e) {}
			} else {
				Process p2;
				try {
					p2 = Runtime.getRuntime().exec("sudo umount " + this.usbDriveName); p2.waitFor();
				} catch (Exception e) {
					e.printStackTrace();
				}
				
				break;
			}
		}
	}
	
	/**
	 * Counts the total amount of files residing in the database and
	 * the total amount of files residing in the root directory.
	 */
	public synchronized void getFilesInDrive() {
		File[] files = new File(this.usbDrivePath).listFiles(File::isFile);
		for(int i = 0; i < files.length; i++) {
			rootFiles+=1;
			filesInDB.put(files[i].getName(), files[i].getName());
		}
		
		File[] directories = new File(this.usbDrivePath).listFiles(File::isDirectory);
		for(int i = 0; i < directories.length; i++) {
			File[] subfiles = new File(directories[i].getPath()).listFiles(File::isFile);
			for(int x = 0; x < subfiles.length; x++) {
				filesInDB.put(subfiles[i].getName(), subfiles[i].getName());
			}
		}
	}
	
	/**
	 * Returns the last integer by which to start making sub directories under the root directory.
	 * Folders are created by the method createSubDirectory().
	 * @return Integer of the folder by which to start indexing sub directories
	 */
	private synchronized int getFolderIndex() {
		if(Files.exists(Paths.get(this.usbDrivePath))) {
			for(int i = 1; i < 1000; i++) {
				if(!Files.exists(Paths.get(this.usbDrivePath + "files_" + String.valueOf(i)+ "/"))) {
					return i;
				} 
			}
		}
		return -1;
	}
	
	/**
	 * Creates a sub directory for the process of reducing the amount of files in the 
	 * root directory of this database.
	 * @return String containing the folder which was created as a sub directory or null if an
	 * exception occurred
	 */
	private synchronized String createSubDirectory() {
		File dir = new File(this.usbDrivePath + "files_" + String.valueOf(this.folderIndex) + "/");
		if(dir.isDirectory()) {
			return this.usbDrivePath + "files_" + String.valueOf(this.folderIndex) + "/";
		} else if(!dir.exists()) {
			try {
				Files.createDirectory(
						Paths.get(this.usbDrivePath + "files_" + String.valueOf(this.folderIndex) + "/"), 
						JSMDBManager.getFullPermissions()
				);
				int i = this.folderIndex;
				this.folderIndex += 1;
				return this.usbDrivePath + "files_" + String.valueOf(i) + "/";
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}
	
	/**
	 * Checks if database is still mounted to original path, 
	 * if folderIndex has not errored, and storage left is
	 * greater than 5%. If database has become invalid, 
	 * it will always remain invalid.
	 * @return boolean indicating validity of database
	 */
	public synchronized boolean isValid() {
		if(this.isValid == false) { return false; }
		
		if(!JSMDBManager.getMountPaths(this.usbDriveName).contains(this.usbDrivePath) ||
		    this.folderIndex == -1 || this.getStorageLeft() <= 5) {
			this.isValid = false;
			return false;
		}
		
		return true;
	}
	
	public synchronized void incrementRootFiles() {
		this.rootFiles+=1;
	}
	
	public synchronized void decrementRootFiles() {
		this.rootFiles-=1;
		if(this.rootFiles < 0) { this.rootFiles = 0; }
	}
	
	public synchronized boolean isCurrentlyWriting(String name) {
		return this.currentlyWriting.get(name) != null;
	}
	
	private synchronized void addToCurrentlyWriting(String name) {
		this.currentlyWriting.put(name, name);
	}
	
	private synchronized void removeFromCurrentlyWriting(String name) {
		this.currentlyWriting.remove(name);
	}
	
	public synchronized int getStorageLeft() {
		return JSMDBManager.storageLeftOnMountedDevice(usbDrivePath, usbDriveName);
	}
	
	private synchronized void addToFilesInDB(String name) {
		filesInDB.put(name, name);
	}
	
	public synchronized boolean isWrittenToDB(String name) {
		return filesInDB.get(name) == null ? false : true;
	}
	
	public synchronized int getTotalFilesInDB() {
		return this.filesInDB.size();
	}
	
	public synchronized void setFileWriteSpeed(int speed) {
		this.fileWriteSpeed = speed;
	}
	
	public synchronized int getFileWriteSpeed() {
		return this.fileWriteSpeed;
	}
	
	public synchronized int getMaxWriters() {
		return this.maxWriters;
	}
	
	public synchronized void setMaxWriters(int amount) {
		this.maxWriters = amount;
	}
	
	public synchronized String getUSBDriveName() {
		return this.usbDriveName;
	}
	
	public synchronized String getUSBDrivePath() {
		return this.usbDrivePath;
	}
	
	public synchronized int getTotalRootFiles() {
		return this.rootFiles;
	}
	
	public synchronized boolean canReduceRoot() {
		return this.reducingRoot.getActiveCount() == 0 &&
				this.reducingRoot.getQueue().size() == 0;
	}
	
	public synchronized boolean isReducingRoot() {
		return this.reducingRoot.getActiveCount() > 0 ||
				this.reducingRoot.getQueue().size() > 0;
	}
	
	public synchronized boolean canWriteFile() {
		return this.fileWriters.getActiveCount() < this.getMaxWriters() &&
				this.fileWriters.getQueue().size() < this.getMaxWriters();
	}
	
	public synchronized boolean isWritingFiles() {
		return this.fileWriters.getActiveCount() > 0 ||
				this.fileWriters.getQueue().size() > 0;
	}
	
	/**
	 * Launches a new thread which reduces the amount of files in the root
	 * directory, by a factor of the amount parameter, and moves them to 
	 * a newly created sub directory.
	 * @param amount - amount of files to move from the root directory
	 * a newly created sub directory. 
	 */
	public synchronized void reduceRoot(int amount) {
		if(this.canReduceRoot()) {
			this.reducingRoot.submit(new Runnable() {
				@Override
				public void run() {
					String folder = createSubDirectory();
					if(folder != null) {
						File[] files = new File(getUSBDrivePath()).listFiles(File::isFile);
						int length = amount > files.length ? files.length : amount;
						for(int i = 0; i < length; i++) {
							try {
								if(!(new File(folder + files[i].getName()).exists()) && !isCurrentlyWriting(files[i].getName())) {
									
									if(files[i].renameTo(new File(folder + files[i].getName()))) {
										decrementRootFiles();
									}
									
								}
								TimeUnit.SECONDS.sleep(1);
							} catch (Exception e) { e.printStackTrace(); }
						}
					}
				}
			});
		}
	}
	
	/**
	 * Writes an image to the database. Will only queue writer if there is 
	 * one available to use.
	 * @param data - raw file data in a byte array
	 * @param name- file name
	 * @return boolean indicating if file was successfully queue to be 
	 * written to the database.
	 */
	public synchronized boolean write(byte[] data, String name) {
		if(this.canWriteFile()) {
			this.addToCurrentlyWriting(name);
			this.fileWriters.submit(new Runnable() {
				@Override
				public void run() {
					try {
						
						if(!Files.exists(Paths.get(getUSBDrivePath() + name))) {
							try {
								Files.createFile(Paths.get(getUSBDrivePath() + name), JSMDBManager.getFullPermissions());
							} catch (IOException e) {
								throw e;
							}
						}
						
						if(writeFile(getUSBDrivePath() + name, convertToByteArrayList(data, 512000))) {
							addToFilesInDB(name);
							incrementRootFiles();
						} else {
							try {
								Files.deleteIfExists(Paths.get(getUSBDrivePath() + name));
							} catch (IOException e) { e.printStackTrace(); }	
						}
					} catch(Exception e) {
						e.printStackTrace();
					} finally {
						removeFromCurrentlyWriting(name);
					}				
				}			
			});
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Writes a list of chunks to a random access file at a rate of database's fileWriteSpeed.
	 * @param path - path to write file too. File should already exist before creating it.
	 * @param chunks - The chunks of the raw byte data to write to the file.
	 * @return boolean indicating success of writing to the database.
	 */
	private boolean writeFile(String path, LinkedList<byte[]> chunks) {
		RandomAccessFile r = null;
		try {
			r = new RandomAccessFile(path, "rw");
			int offset = 0;
			while(!chunks.isEmpty()) {
				byte[] chunk = chunks.pop();
				r.seek(offset);
				r.write(chunk);
				offset+=chunk.length;
				try { TimeUnit.MILLISECONDS.sleep(this.fileWriteSpeed); } catch (InterruptedException e) {}
			}
			r.close();
			return true;
		} catch (Exception e) {
			try { r.close(); } catch (IOException e1) {}
			return false;
		}
	}
	
	private static LinkedList<byte[]> convertToByteArrayList(byte[] data, int fileBuffer) {
		LinkedList<byte[]> result = new LinkedList<byte[]>();
		int from = 0;
		int to = 0;
		int slicedItems = 0;
		while (slicedItems < data.length) {
			to = from + Math.min(fileBuffer, data.length - to);
			byte[] slice = Arrays.copyOfRange(data, from, to);
			result.add(slice);
			slicedItems += slice.length;
			from = to;
		}
		return result;
	}

}
