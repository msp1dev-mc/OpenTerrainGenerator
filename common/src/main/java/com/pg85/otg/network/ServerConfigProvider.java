package com.pg85.otg.network;

import com.pg85.otg.LocalBiome;
import com.pg85.otg.LocalWorld;
import com.pg85.otg.OTG;
import com.pg85.otg.configuration.biome.BiomeConfig;
import com.pg85.otg.configuration.biome.BiomeConfigFinder;
import com.pg85.otg.configuration.biome.BiomeLoadInstruction;
import com.pg85.otg.configuration.biome.BiomeConfigFinder.BiomeConfigStub;
import com.pg85.otg.configuration.io.FileSettingsReader;
import com.pg85.otg.configuration.io.FileSettingsWriter;
import com.pg85.otg.configuration.io.SettingsMap;
import com.pg85.otg.configuration.standard.BiomeStandardValues;
import com.pg85.otg.configuration.standard.PluginStandardValues;
import com.pg85.otg.configuration.standard.WorldStandardValues;
import com.pg85.otg.configuration.world.WorldConfig;
import com.pg85.otg.logging.LogMarker;
import com.pg85.otg.util.BiomeIds;
import com.pg85.otg.util.helpers.FileHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

/**
 * Holds the WorldConfig and all BiomeConfigs.
 *
 * <h3>A note about {@link LocalWorld} usage</h3>
 * <p>Currently, a {@link LocalWorld} instance is passed to the constructor of
 * this class. That is bad design. The plugin should be able to read the
 * settings and then create a world based on that. Now the world is created, and
 * then the settings are injected. It is also strange that the configuration
 * code is now able to spawn a cow, to give one example.</p>
 *
 * <p>Fixing that will be a lot of work - {@link LocalWorld} is currently a God
 * class that is required everywhere. If a rewrite of that class is ever
 * planned, be sure to split that class up!</p>
 */
public final class ServerConfigProvider implements ConfigProvider
{
    private static final int MAX_INHERITANCE_DEPTH = 15;
    private LocalWorld world;
    private File settingsDir;
    private WorldConfig worldConfig;

    /**
     * Holds all biome configs. Generation Id => BiomeConfig
     * <p>
     * Must be simple array for fast access. Warning: some ids may contain
     * null values, always check.
     */
    private LocalBiome[] biomesByOTGId;
    
    private LocalBiome[] biomesBySavedId;

    /**
     * The number of loaded biomes.
     */
    private int biomesCount;

    /**
     * Loads the settings from the given directory for the given world.
     * @param settingsDir The directory to load from.
     * @param world       The world to load the settings for.
     */
    public ServerConfigProvider(File settingsDir, LocalWorld world, File worldSaveFolder)
    {
        this.settingsDir = settingsDir;
        this.world = world;
        this.biomesByOTGId = new LocalBiome[world.getMaxBiomesCount()];
        this.biomesBySavedId = new LocalBiome[world.getMaxBiomesCount()];
        
        loadSettings(worldSaveFolder);
    }

    /**
     * Loads all settings. Expects the biomes array to be empty (filled with
     * nulls), the savedBiomes collection to be empty and the biomesCount
     * field to be zero.
     */
    private void loadSettings(File worldSaveFolder)
    {   	
        SettingsMap worldConfigSettings = loadWorldConfig();
        loadBiomes(worldConfigSettings, worldSaveFolder);

        // We have to wait for the loading in order to get things like
        // temperature
        worldConfig.biomeGroupManager.processBiomeData(world);
    }

    private SettingsMap loadWorldConfig()
    {
        File worldConfigFile = new File(settingsDir, WorldStandardValues.WORLD_CONFIG_FILE_NAME);
        SettingsMap settingsMap = FileSettingsReader.read(world.getName(), worldConfigFile);

    	ArrayList<String> biomes = new ArrayList<String>();
    	File biomesDirectory = new File(settingsDir, WorldStandardValues.WORLD_BIOMES_DIRECTORY_NAME); 	

    	if (biomesDirectory.exists() && biomesDirectory.isDirectory())
		{
    		AddBiomesFromDirRecursive(biomes, biomesDirectory);
		}

        this.worldConfig = new WorldConfig(settingsDir, settingsMap, world, biomes);
        FileSettingsWriter.writeToFile(worldConfig.getSettingsAsMap(), worldConfigFile, worldConfig.SettingsMode);

        return settingsMap;
    }
    
    void AddBiomesFromDirRecursive(ArrayList<String> biomes, File biomesDirectory)
    {
    	for(File biomeConfig : biomesDirectory.listFiles())
    	{
    		if(biomeConfig.isFile() && biomeConfig.getName().endsWith(BiomeStandardValues.BIOME_CONFIG_EXTENSION.getDefaultValue()))
    		{
    			biomes.add(biomeConfig.getName().replace(BiomeStandardValues.BIOME_CONFIG_EXTENSION.getDefaultValue(), ""));
    		}
    		else if(biomeConfig.isDirectory())
    		{
    			AddBiomesFromDirRecursive(biomes, biomeConfig);
    		}
    	}
    }

    public void saveWorldConfig()
    {
    	File worldConfigFile = new File(settingsDir, WorldStandardValues.WORLD_CONFIG_FILE_NAME);
    	FileSettingsWriter.writeToFile(worldConfig.getSettingsAsMap(), worldConfigFile, worldConfig.SettingsMode);
    }

    private void loadBiomes(SettingsMap worldConfigSettings, File worldSaveFolder)
    {
        // Establish folders
        List<File> biomeDirs = new ArrayList<File>(2);
        // OpenTerrainGenerator/Presets/<WorldName>/<WorldBiomes/
        biomeDirs.add(new File(settingsDir, correctOldBiomeConfigFolder(settingsDir)));
        // OpenTerrainGenerator/GlobalBiomes/
        biomeDirs.add(new File(OTG.getEngine().getOTGDataFolder(), PluginStandardValues.BiomeConfigDirectoryName));

        FileHelper.makeFolders(biomeDirs);

        // Build a set of all biomes to load
        Collection<BiomeLoadInstruction> biomesToLoad = new HashSet<BiomeLoadInstruction>();

        // If we're creating a new world with new configs then add the default biomes
        if(worldConfigSettings.isNewConfig())
        {
        	Collection<? extends BiomeLoadInstruction> defaultBiomes = world.getDefaultBiomes();
            for (BiomeLoadInstruction defaultBiome : defaultBiomes)
            {
        		worldConfig.worldBiomes.add(defaultBiome.getBiomeName());
        		biomesToLoad.add(new BiomeLoadInstruction(defaultBiome.getBiomeName(), defaultBiome.getBiomeTemplate()));
            }
        }
        
        // Load all files
        BiomeConfigFinder biomeConfigFinder = new BiomeConfigFinder(OTG.getPluginConfig().biomeConfigExtension);
        Map<String, BiomeConfigStub> biomeConfigStubs = biomeConfigFinder.findBiomes(worldConfig, world, worldConfig.worldHeightScale, biomeDirs, biomesToLoad);
        
        // Read all settings
        Map<String, BiomeConfig> loadedBiomes = readAndWriteSettings(worldConfigSettings, biomeConfigStubs);

        // Index all necessary settings
        String loadedBiomeNames = indexSettings(worldConfig.customBiomeGenerationIds, worldConfigSettings.isNewConfig(), loadedBiomes, worldSaveFolder);

        OTG.log(LogMarker.INFO, "{} biomes Loaded", biomesCount);
        OTG.log(LogMarker.TRACE, "{}", loadedBiomeNames);
    }

    @Override
    public WorldConfig getWorldConfig()
    {
        return worldConfig;
    }

    @Override
    public LocalBiome getBiomeBySavedIdOrNull(int id)
    {
        if (id < 0 || id > biomesBySavedId.length)
        {
            return null;
        }
        return biomesBySavedId[id];
    }
    
    @Override
    public LocalBiome getBiomeByOTGIdOrNull(int id)
    {
        if (id < 0 || id > biomesByOTGId.length)
        {
            return null;
        }
        return biomesByOTGId[id];
    }

    @Override
    public void reload()
    {
        // Clear biome collections
        Arrays.fill(this.biomesByOTGId, null);
        Arrays.fill(this.biomesBySavedId, null);
        this.biomesCount = 0;

        // Load again
        loadSettings(this.world.getWorldSaveDir());
    }

    private Map<String, BiomeConfig> readAndWriteSettings(SettingsMap worldConfigSettings, Map<String, BiomeConfigStub> biomeConfigStubs)
    {
        Map<String, BiomeConfig> loadedBiomes = new HashMap<String, BiomeConfig>();

        for (BiomeConfigStub biomeConfigStub : biomeConfigStubs.values())
        {
            // Allow to let world settings influence biome settings
            //biomeConfigStub.getSettings().setFallback(worldConfigSettings); // TODO: Make sure this can be removed safely

            // Inheritance
            processInheritance(biomeConfigStubs, biomeConfigStub, 0);
            processMobInheritance(biomeConfigStubs, biomeConfigStub, 0);

            // Settings reading
            BiomeConfig biomeConfig = new BiomeConfig(biomeConfigStub.getLoadInstructions(), biomeConfigStub, biomeConfigStub.getSettings(), worldConfig);
            loadedBiomes.put(biomeConfigStub.getBiomeName(), biomeConfig);

            // Settings writing
            File writeFile = biomeConfigStub.getFile();
            if (!biomeConfig.biomeExtends.isEmpty())
            {
                writeFile = new File(writeFile.getAbsolutePath() + ".inherited");
            }
            FileSettingsWriter.writeToFile(biomeConfig.getSettingsAsMap(), writeFile, worldConfig.SettingsMode);
        }

        return loadedBiomes;
    }

    private int getRequestedSavedId(String resourceLocation)
    {
    	return world.getRegisteredBiomeId(resourceLocation);
    }

    private String indexSettings(Map<String, Integer> worldBiomes, boolean isNewWorldConfig, Map<String, BiomeConfig> loadedBiomes, File worldSaveFolder)
    {
        StringBuilder loadedBiomeNames = new StringBuilder();

        List<BiomeConfig> loadedBiomeList = new ArrayList<BiomeConfig>(loadedBiomes.values());
       
        ArrayList<BiomeConfig> nonVirtualBiomesExisting = new ArrayList<BiomeConfig>();    
        ArrayList<BiomeConfig> nonVirtualBiomes = new ArrayList<BiomeConfig>();
        ArrayList<BiomeConfig> virtualBiomesExisting = new ArrayList<BiomeConfig>();
        ArrayList<BiomeConfig> virtualBiomes = new ArrayList<BiomeConfig>();
               
        // If this is a previously created world then load the biome id data and register biomes to the same OTG biome id as before.
        ArrayList<BiomeIdData> loadedBiomeIdData = LoadBiomeIdData(worldSaveFolder);
        boolean hasWorldData = loadedBiomeIdData != null;
        if(hasWorldData)
        {
        	boolean bFound = false;
        	for(BiomeIdData biomeIdData : loadedBiomeIdData)
        	{
    			if(biomeIdData.biomeName.startsWith(world.getName() + "_"))
    			{
    				bFound = true;
    				break;
    			}
        	}
        	hasWorldData = bFound;
        }

        // Update configs for worlds with no saved biome id data
        if(!hasWorldData)
        {
        	for(Entry<String, BiomeConfig> entry : loadedBiomes.entrySet())
        	{
	        	// Update biomes for legacy worlds, default biomes should be referred to as minecraft:<biomename>
	        	if(
        			entry.getValue().replaceToBiomeName != null && 
					entry.getValue().replaceToBiomeName.trim().length() > 0	        			
				)
	        	{
	        		String defaultBiomeResourceLocation = OTG.getRegistryNameForDefaultBiome(entry.getValue().replaceToBiomeName);
	        		if(defaultBiomeResourceLocation != null)
	        		{
	        			entry.getValue().replaceToBiomeName = defaultBiomeResourceLocation;
	        		}
	        	} else {
	        		// Default biomes must replacetobiomename themselves
	        		String defaultBiomeResourceLocation = OTG.getRegistryNameForDefaultBiome(entry.getValue().getName());
	        		if(defaultBiomeResourceLocation != null)
	        		{
	        			entry.getValue().replaceToBiomeName = defaultBiomeResourceLocation;
	        		}
	        	}
        	}
        }
        
        // For backwards compatibility load custom biomes from the world config
        // A world created with a previous version of OTG may not have worlddata
        if(!OTG.isNewWorldBeingCreated && !hasWorldData && worldBiomes.size() > 0)
        {
        	loadedBiomeIdData = new ArrayList<BiomeIdData>();
	        for(Entry<String, Integer> worldBiome : worldBiomes.entrySet())
	        {
	        	BiomeConfig biomeConfig = loadedBiomes.get(worldBiome.getKey());
	        	
	        	loadedBiomeIdData.add(
        			new BiomeIdData(
    					world.getName() + "_" + worldBiome.getKey(), 
    					worldBiome.getValue(), 
    					worldBiome.getValue() > 255 || (
							biomeConfig.replaceToBiomeName != null && 
							biomeConfig.replaceToBiomeName.trim().length() > 0
						) ? -1 : worldBiome.getValue()
					)
    			);
	        }
	        if(loadedBiomeIdData.size() == 0)
	        {
	        	loadedBiomeIdData = null;
	        }
        }
        
        if(loadedBiomeIdData != null)
        {
        	for(BiomeIdData biomeIdData : loadedBiomeIdData)
        	{
        		if(biomeIdData.biomeName.startsWith(world.getName() + "_"))
        		{
            		for(BiomeConfig biomeConfig : loadedBiomeList)
            		{
            			if((world.getName() + "_" + biomeConfig.getName()).equals(biomeIdData.biomeName))
            			{
        	            	if(OTG.getEngine().isOTGBiomeIdAvailable(world.getName(), biomeIdData.otgBiomeId))
        	            	{
                				OTG.getEngine().setOTGBiomeId(world.getName(), biomeIdData.otgBiomeId, biomeConfig, false);        	            		
        	            	}
        	            	else if((world.getName() + "_" + OTG.getEngine().getOTGBiomeIds(world.getName())[biomeIdData.otgBiomeId].getName()).equals(biomeIdData.biomeName))
        	            	{
        	            		OTG.getEngine().setOTGBiomeId(world.getName(), biomeIdData.otgBiomeId, biomeConfig, true);
        	            	} else {
        	            		// The only time a biomeId can be claimed already is when an 
        	            		// unloaded world is being reloaded.
        	            		throw new RuntimeException("This shouldn't happen");
        	            	}

            	        	if(biomeIdData.otgBiomeId > -1 && biomeIdData.otgBiomeId < 256)
            	        	{
            	        		nonVirtualBiomesExisting.add(biomeConfig);
            	        	}
            	        	else if(biomeIdData.otgBiomeId > 255)
            	        	{
            	        		virtualBiomesExisting.add(biomeConfig);
            	        	}  
            				break;
            			}
            		}
        		}
        	}
        }
        
        OTG.log(LogMarker.INFO, "indexSettings start");
        // Set OTG biome id's for biomes, make sure there is enough space to register all biomes.
        for (BiomeConfig biomeConfig : loadedBiomeList)
        {     
        	OTG.log(LogMarker.INFO, "indexSettings0: " + biomeConfig.getName());
        	
            // Statistics of the loaded biomes
            this.biomesCount++;
            loadedBiomeNames.append(biomeConfig.getName());
            loadedBiomeNames.append(", ");

            BiomeConfig[] otgIds2 = OTG.getEngine().getOTGBiomeIds(world.getName());
            
            int otgBiomeId = -1;
            
            // Exclude already registered biomes from loadedBiomeIdData / default biomes
            boolean bFound = false;
            for(int i = 0; i < otgIds2.length; i++)
            {
            	BiomeConfig biomeConfig2 = otgIds2[i];
            	if(biomeConfig == biomeConfig2)
            	{
            		bFound = true;
            		break;            		
            	}
        		// Forge dimensions: If a world is being reloaded after being unloaded replace the existing biomeConfig
            	else if(
        			biomeConfig2 != null &&
        			biomeConfig.getName().equals(biomeConfig2.getName()) &&
        			biomeConfig.worldConfig.getName().equals(biomeConfig2.worldConfig.getName())
    			)
            	{
            		OTG.getEngine().setOTGBiomeId(world.getName(), i, biomeConfig2, true);
            		otgBiomeId = i;
            		break;
            	}
            }
            if(bFound)
            {
            	continue; // biome is from loadedBiomeIdData, already registered.
            }
            
            if(otgBiomeId == -1)
            {          
            	// Find the next available id
	            for(int i = (!biomeConfig.replaceToBiomeName.isEmpty() ? 256 : 0); i < otgIds2.length; i++) // Virtual (replacetobiomename) biomes can only have id's above 255
	            {
	            	if((biomeConfig.replaceToBiomeName.isEmpty() && i > 255) || (biomeConfig.replaceToBiomeName.isEmpty() && i >= OTG.getEngine().getOTGBiomeIds(world.getName()).length))
	            	{
	            		throw new RuntimeException("Biome could not be registered, no free biome id's!");
	            	}
	            	if(OTG.getEngine().isOTGBiomeIdAvailable(world.getName(), i))
	            	{
	            		otgBiomeId = i;
	            		OTG.getEngine().setOTGBiomeId(world.getName(), i, biomeConfig, false);
	            		break;
	            	}
	            }
	        	if(otgBiomeId > -1 && otgBiomeId < 256)
	        	{
	        		nonVirtualBiomes.add(biomeConfig);
	        	}
	        	else if(otgBiomeId > 255)
	        	{
	        		virtualBiomes.add(biomeConfig);
	        	}
	        	OTG.log(LogMarker.INFO, "indexSettings1: " + biomeConfig.getName() + " " + otgBiomeId);
            }        	
        }
                
        // When loading an existing world load the existing biomes first, new biomes after so they don't claim reserved biome id's.
        for (BiomeConfig biomeConfig : nonVirtualBiomesExisting)
        {
        	CreateAndRegisterBiome(loadedBiomeIdData, biomeConfig);
        }
        for (BiomeConfig biomeConfig : virtualBiomesExisting)
        {
        	CreateAndRegisterBiome(loadedBiomeIdData, biomeConfig);
        }
        for (BiomeConfig biomeConfig : nonVirtualBiomes)
        {
        	CreateAndRegisterBiome(loadedBiomeIdData, biomeConfig);
        }
        for (BiomeConfig biomeConfig : virtualBiomes)
        {
        	CreateAndRegisterBiome(loadedBiomeIdData, biomeConfig);
        }
        
        SaveBiomeIdData(worldSaveFolder);
        
        // Forge dimensions are seperate worlds that can share biome configs so
        // use the highest maxSmoothRadius of any of the loaded worlds.
        // Worlds loaded before this one will not use biomes from this world
        // so no need to change their this.worldConfig.maxSmoothRadius
        ArrayList<LocalWorld> worlds = OTG.getAllWorlds();
        if(worlds != null)
        {
	        for(LocalWorld world : worlds)
	        {
	            if (this.worldConfig.maxSmoothRadius < world.getConfigs().getWorldConfig().maxSmoothRadius)
	            {
	                this.worldConfig.maxSmoothRadius = world.getConfigs().getWorldConfig().maxSmoothRadius;
	            }
	        }
        }

        if (this.biomesCount > 0)
        {
            // Remove last ", "
            loadedBiomeNames.delete(loadedBiomeNames.length() - 2, loadedBiomeNames.length());
        }
        return loadedBiomeNames.toString();
    }
    
    // TODO: Clean up this class, split it up into serverconfigprovider / biome loading
    
    // Saving / Loading
    // TODO: It's crude but it works, can improve later

	public void SaveBiomeIdData(File worldSaveDir)
	{
        // If this is a previously created world then register biomes to the same OTG biome id as before.
        ArrayList<BiomeIdData> loadedBiomeIdData = LoadBiomeIdData(worldSaveDir);
		
		File biomeIdDataFile = new File(worldSaveDir + "/OpenTerrainGenerator/BiomeIds.txt");
		if(biomeIdDataFile.exists())
		{
			biomeIdDataFile.delete();
		}

		StringBuilder stringbuilder = new StringBuilder();

        if(loadedBiomeIdData != null)
        {
    		for(BiomeIdData biomeIdData : loadedBiomeIdData)
    		{
    			if(!biomeIdData.biomeName.startsWith(world.getName() + "_"))
    			{
    				stringbuilder.append((stringbuilder.length() == 0 ? "" : ",") + biomeIdData.biomeName + "," + biomeIdData.savedBiomeId + "," + biomeIdData.otgBiomeId);
    			}
			}
        }

		for(LocalBiome biome : this.biomesByOTGId)
		{
			if(biome != null)
			{
				stringbuilder.append((stringbuilder.length() == 0 ? "" : ",") + world.getName() + "_" + biome.getName() + "," + biome.getIds().getSavedId() + "," + biome.getIds().getOTGBiomeId());
			}			 
		}

		BufferedWriter writer = null;
        try
        {
        	biomeIdDataFile.getParentFile().mkdirs();
        	writer = new BufferedWriter(new FileWriter(biomeIdDataFile));
            writer.write(stringbuilder.toString());
            OTG.log(LogMarker.TRACE, "Custom dimension data saved");
        }
        catch (IOException e)
        {
        	OTG.log(LogMarker.ERROR, "Could not save custom dimension data.");
            e.printStackTrace();
        }
        finally
        {
            try
            {
                writer.close();
            } catch (Exception e) { }
        }
	}
	
	class BiomeIdData
	{
		public String biomeName;
		public int otgBiomeId;
		public int savedBiomeId;
		
		public BiomeIdData() {}
		
		public BiomeIdData(String biomeName, int otgBiomeId, int savedBiomeId)
		{
			this.biomeName = biomeName;
			this.otgBiomeId = otgBiomeId;
			this.savedBiomeId = savedBiomeId;
		}
	}
	
	public ArrayList<BiomeIdData> LoadBiomeIdData(File worldSaveDir)
	{
		File biomeIdDataFile = new File(worldSaveDir + "/OpenTerrainGenerator/BiomeIds.txt");
		String[] biomeIdDataFileValues = {};
		if(biomeIdDataFile.exists())
		{
			try {
				StringBuilder stringbuilder = new StringBuilder();
				BufferedReader reader = new BufferedReader(new FileReader(biomeIdDataFile));
				try {
					String line = reader.readLine();

				    while (line != null)
				    {
				    	stringbuilder.append(line);
				        line = reader.readLine();
				    }
				    if(stringbuilder.length() > 0)
				    {
				    	biomeIdDataFileValues = stringbuilder.toString().split(",");
				    }
				    OTG.log(LogMarker.TRACE, "Biome Id data loaded");
				} finally {
					reader.close();
				}

			} catch (FileNotFoundException e1) {
				e1.printStackTrace();
			}
			catch (IOException e1) {
				e1.printStackTrace();
			}
		}
		
		ArrayList<BiomeIdData> biomeIdDatas = new ArrayList<BiomeIdData>();
		if(biomeIdDataFileValues.length > 0)
		{
			for(int i = 0; i < biomeIdDataFileValues.length; i += 3)
			{
				BiomeIdData biomeIdData = new BiomeIdData();
				biomeIdData.biomeName = biomeIdDataFileValues[i];
				biomeIdData.savedBiomeId = Integer.parseInt(biomeIdDataFileValues[i + 1]);
				biomeIdData.otgBiomeId = Integer.parseInt(biomeIdDataFileValues[i + 2]);				
				biomeIdDatas.add(biomeIdData);
			}
		}

		return biomeIdDatas.size() == 0 ? null : biomeIdDatas;
	}
    
	//
	
    private void CreateAndRegisterBiome(ArrayList<BiomeIdData> loadedBiomeIdData, BiomeConfig biomeConfig)
    {   	    	
    	// Restore the saved id (if any)
    	int savedBiomeId = -1;
        if(loadedBiomeIdData != null)
        {
        	for(BiomeIdData biomeIdData : loadedBiomeIdData)
        	{
    			if((world.getName() + "_" + biomeConfig.getName()).equals(biomeIdData.biomeName))
    			{
    				savedBiomeId = biomeIdData.savedBiomeId;
    				break;
    			}
        	}
        }
        
        // Get the assigned OTG biome id
    	int otgBiomeId = -1;
    	BiomeConfig[] otgIds2 = OTG.getEngine().getOTGBiomeIds(world.getName());
    	for(int i = 0; i < otgIds2.length; i++)
    	{
    		if(otgIds2[i] == biomeConfig)
    		{
    			otgBiomeId = i;
    			break;
    		}
    	}
    	if(otgBiomeId == -1)
    	{
    		throw new RuntimeException("Biome was not registered, most likely there were no id's available.");
    	}

        OTG.log(LogMarker.INFO, "CreateAndRegisterBiome1: " + biomeConfig.getName() + " " + otgBiomeId + " " + savedBiomeId);
    	
        // Get correct saved id (defaults to generation id, but can be set to use the generation id of another biome)
        if (!biomeConfig.replaceToBiomeName.isEmpty())
        {
        	// This won't work when trying to replacetobiomename a replacetobiomename biome. ReplaceToBiomeName biome must be non-virtual. 
        	for(int i = 0; i < otgIds2.length; i++)
        	{
        		if(otgIds2[i] != null && otgIds2[i].getName() == biomeConfig.replaceToBiomeName)
        		{
        			savedBiomeId = i;
        			break;
    			}
        	}
        	if(savedBiomeId == -1)
        	{
        		savedBiomeId = getRequestedSavedId(biomeConfig.replaceToBiomeName);
        	}
        	
        	if(savedBiomeId == -1)
        	{
            	String[] replaceToBiomeNameArr = biomeConfig.replaceToBiomeName.split(",");
        		if(replaceToBiomeNameArr.length == 1)
        		{
	        		// This may be a legacy world that doesn't use resourcelocation notation, get the correct registry name
	        		String replaceToBiomeNameNew = OTG.getRegistryNameForDefaultBiome(biomeConfig.replaceToBiomeName);
	        		
	        		if(replaceToBiomeNameNew != null)
	        		{
		        		savedBiomeId = getRequestedSavedId(replaceToBiomeNameNew);
		        		if(savedBiomeId != -1)
		        		{
		        			biomeConfig.replaceToBiomeName = replaceToBiomeNameNew;
		        		}
	        		}
        		}
        	}
        	
        	if(savedBiomeId == -1 || savedBiomeId > 255)
        	{
        		LocalBiome biome = world.getBiomeByNameOrNull(worldConfig.defaultOceanBiome);
        		if(biome != null)
        		{
        			savedBiomeId = biome.getIds().getOTGBiomeId(); // TODO: Re-implement replacetobiomename:virtualbiome
        		} else {
            		savedBiomeId = getRequestedSavedId(biomeConfig.replaceToBiomeName);
        			throw new RuntimeException("ReplaceToBiomeName: " + biomeConfig.replaceToBiomeName + " for biome " + biomeConfig.getName() + " could not be found. Please note that it is not possible to ReplaceToBiomeName to a ReplaceToBiomeName biome. Please update your biome configs.");
        		}
    		}
        }
        
        OTG.log(LogMarker.INFO, "CreateAndRegisterBiome2: " + biomeConfig.getName() + " " + otgBiomeId + " " + savedBiomeId);
        
        // Create biome
        LocalBiome biome = world.createBiomeFor(biomeConfig, new BiomeIds(otgBiomeId, savedBiomeId), this);
        
        this.biomesByOTGId[biome.getIds().getOTGBiomeId()] = biome;
        if(biome.getIds().getSavedId() == biome.getIds().getOTGBiomeId() || OTG.getRegistryNameForDefaultBiome(biome.getBiomeConfig().getName()) != null) // Non-virtual and default biomes only
        {
        	this.biomesBySavedId[biome.getIds().getSavedId()] = biome;
        }

        // Indexing ReplacedBlocks
        if (!this.worldConfig.BiomeConfigsHaveReplacement)
        {
            this.worldConfig.BiomeConfigsHaveReplacement = biomeConfig.replacedBlocks.hasReplaceSettings();
        }

        // Indexing MaxSmoothRadius
        if (this.worldConfig.maxSmoothRadius < biomeConfig.smoothRadius)
        {
            this.worldConfig.maxSmoothRadius = biomeConfig.smoothRadius;
        }

        // Indexing BiomeColor
        if (this.worldConfig.biomeMode == OTG.getBiomeModeManager().FROM_IMAGE)
        {
            if (this.worldConfig.biomeColorMap == null)
            {
                this.worldConfig.biomeColorMap = new HashMap<Integer, Integer>();
            }

            int color = biomeConfig.biomeColor;
            this.worldConfig.biomeColorMap.put(color, biome.getIds().getOTGBiomeId());
        }
    }

    private void processInheritance(Map<String, BiomeConfigStub> biomeConfigStubs, BiomeConfigStub biomeConfigStub, int currentDepth)
    {
        if (biomeConfigStub.biomeExtendsProcessed)
        {
            // Already processed
            return;
        }

        String extendedBiomeName = biomeConfigStub.getSettings().getSetting(BiomeStandardValues.BIOME_EXTENDS);
        if (extendedBiomeName.isEmpty())
        {
            // Not extending anything
            biomeConfigStub.biomeExtendsProcessed = true;
            return;
        }

        // This biome extends another biome
        BiomeConfigStub extendedBiomeConfig = biomeConfigStubs.get(extendedBiomeName);
        if (extendedBiomeConfig == null)
        {
            OTG.log(LogMarker.WARN, "The biome {} tried to extend the biome {}, but that biome doesn't exist.",
                    biomeConfigStub.getBiomeName(), extendedBiomeName);
            return;
        }

        // Check for too much recursion
        if (currentDepth > MAX_INHERITANCE_DEPTH)
        {
            OTG.log(LogMarker.FATAL,
                    "The biome {} cannot extend the biome {} - too much configs processed already! Cyclical inheritance?",
                    biomeConfigStub.getBiomeName(), extendedBiomeConfig.getBiomeName());
        }

        if (!extendedBiomeConfig.biomeExtendsProcessed)
        {
            // This biome has not been processed yet, do that first
            processInheritance(biomeConfigStubs, extendedBiomeConfig, currentDepth + 1);
        }

        // Merge the two
        biomeConfigStub.getSettings().setFallback(extendedBiomeConfig.getSettings());

        // Done
        biomeConfigStub.biomeExtendsProcessed = true;
    }

    private void processMobInheritance(Map<String, BiomeConfigStub> biomeConfigStubs, BiomeConfigStub biomeConfigStub, int currentDepth)
    {
        if (biomeConfigStub.inheritMobsBiomeNameProcessed)
        {
            // Already processed
            return;
        }

        String stubInheritMobsBiomeName = biomeConfigStub.getSettings().getSetting(BiomeStandardValues.INHERIT_MOBS_BIOME_NAME, biomeConfigStub.getLoadInstructions().getBiomeTemplate().defaultInheritMobsBiomeName);

        if(stubInheritMobsBiomeName != null && stubInheritMobsBiomeName.length() > 0)
        {
            String[] inheritMobsBiomeNames = stubInheritMobsBiomeName.split(",");
	        for(String inheritMobsBiomeName : inheritMobsBiomeNames)
	        {
	            if (inheritMobsBiomeName.isEmpty())
	            {
	                // Not extending anything
	                continue;
	            }

		        // This biome inherits mobs from another biome
		        BiomeConfigStub inheritMobsBiomeConfig = biomeConfigStubs.get(inheritMobsBiomeName);

		        if (inheritMobsBiomeConfig == null || inheritMobsBiomeConfig == biomeConfigStub) // Most likely a legacy config that is not using resourcelocation yet, for instance: Plains instead of minecraft:plains. Try to convert.
		        {
		        	String vanillaBiomeName = OTG.getRegistryNameForDefaultBiome(inheritMobsBiomeName);
		        	if(vanillaBiomeName != null)
		        	{
		        		inheritMobsBiomeConfig = null;
		        		inheritMobsBiomeName = vanillaBiomeName;
		        	}
		        	else if(inheritMobsBiomeConfig == biomeConfigStub)
		        	{
			            OTG.log(LogMarker.TRACE, "The biome {} tried to inherit mobs from itself.", new Object[] { biomeConfigStub.getBiomeName()});
			            continue;
		        	}
		        }
		        
		        // Check for too much recursion
		        if (currentDepth > MAX_INHERITANCE_DEPTH)
		        {
		            OTG.log(LogMarker.FATAL, "The biome {} cannot inherit mobs from biome {} - too much configs processed already! Cyclical inheritance?", new Object[] { biomeConfigStub.getFile().getName(), inheritMobsBiomeConfig.getFile().getName()});
		        }

		        if(inheritMobsBiomeConfig != null)
		        {
			        if (!inheritMobsBiomeConfig.inheritMobsBiomeNameProcessed)
			        {
			            // This biome has not been processed yet, do that first
			            processMobInheritance(biomeConfigStubs, inheritMobsBiomeConfig, currentDepth + 1);
			        }

			        // Merge the two
			        biomeConfigStub.mergeMobs(inheritMobsBiomeConfig);
		        } else {

		        	// This is a vanilla biome or a biome added by another mod.
		        	world.mergeVanillaBiomeMobSpawnSettings(biomeConfigStub, inheritMobsBiomeName);
			        continue;
		        }
	        }

	        // Done
	        biomeConfigStub.inheritMobsBiomeNameProcessed = true;
        }
    }

    private String correctOldBiomeConfigFolder(File settingsDir)
    {
        // Rename the old folder
        String biomeFolderName = WorldStandardValues.WORLD_BIOMES_DIRECTORY_NAME;
        File oldBiomeConfigs = new File(settingsDir, "BiomeConfigs");
        if (oldBiomeConfigs.exists())
        {
            if (!oldBiomeConfigs.renameTo(new File(settingsDir, biomeFolderName)))
            {
                OTG.log(LogMarker.WARN, "========================");
                OTG.log(LogMarker.WARN, "Fould old `BiomeConfigs` folder, but it could not be renamed to `", biomeFolderName, "`!");
                OTG.log(LogMarker.WARN, "Please rename the folder manually.");
                OTG.log(LogMarker.WARN, "========================");
                biomeFolderName = "BiomeConfigs";
            }
        }
        return biomeFolderName;
    }

    @Override
    public LocalBiome[] getBiomeArrayByOTGId()
    {
        return this.biomesByOTGId;
    }
}
