# jsmdatabase
A file database for when your on a budget

This is a non-redundant, low resource, high throughput, file database.
What does that mean?

### non-redundant ###
  * No file is ever written twice. Once it's in the database, it will not be overridden.
### low resource ###
  * If your hardware is on a budget, like a raspberry pi, you can throttle the speed of the database down as
    to not consume many resources. Set the total `maxDatabaseWriters`, the `maxDBs`, and the `databaseWriteSpeed` to
    whatever best fits your device.
### high throughput ###
  * This database can take a beating. While it does have it's limits, write as big of a file as you want to it as many times 
    as you want from multiple different devices. As long as the memory can handle the data, this database will never
    exceed it's restrictive limitations.
### file database ###
  * This database stores whatever files you want without redundancy. It's perfect for a timelapse setup: when sets of data
    are needed to be stored on an interval.
    
    
Example...

`
JSMDBManager dbManager = new JSMDBManager();
dbManager.setMaxDatabases(2); //Total of 2 usb devices plugged into machine
dbManager.setDatabaseWriteSpeed(50); //50 millisecond write speed
dbManager.setMaxDatabaseWriters(4); //Total concurrent file writers
dbManager.setInfoFileDirectory('/home/user/test/info/'); //Stores information about usb devices
dbManager.setRootDBDirectory('/home/user/test/rootdb/'); //Stores the files!
dbManager.setLinuxScriptsDirectory('/home/user/test/scripts/'); //Stores the helper scripts
dbManager.init(); //Generates the scripts, creates directories, and mounts usb drives
dbManager.start(); //Runs the thread to unmount drives and store information about them
`
You will have to create a custom controller for the database. But that should be really easy...

`
TimeUnit.MILLISECONDS.sleep(1000);
				
if(photoStore.size() >= 100) {
	//Force the database to write to the default directory if nessesary!		
	LinkedList<PhotoStore.Photo> photos = photoStore.subset(50);
	if(photos != null) {
		for(PhotoStore.Photo photo : photos) {
			if(dbManager.isWrittenToDB(photo.getName())) {
				photoStore.remove(photo.getName());
			} else if(!dbManager.isCurrentlyWriting(photo.getName())){
				dbManager.save(photo.getData(), photo.getName(), true);
			}
	}
}
						
} else {
						
LinkedList<PhotoStore.Photo> photos = photoStore.subset(16);
if(photos != null) {
	for(PhotoStore.Photo photo : photos) {
		if(dbManager.isWrittenToDB(photo.getName())) {
			photoStore.remove(photo.getName());
		} else if(!dbManager.isCurrentlyWriting(photo.getName())) {
			dbManager.save(photo.getData(), photo.getName(), false);
		}
	}
}						
}	
					
//If you want to limit how many files can be in the root directory of each database
for(JSMDatabase db : dbManager.getDatabases()) {
	if(db.canReduceRoot() && db.getTotalRootFiles() >= 50) {
		db.reduceRoot(20);
	}
}
`
