package mindustry.game;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.io.legacy.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

/** Updates and handles state of the campaign universe. Has no relevance to other gamemodes. */
public class Universe{
    private int seconds;
    private int netSeconds;
    private float secondCounter;
    private int turn;
    private float turnCounter;

    private @Nullable Schematic lastLoadout;
    private ItemSeq lastLaunchResources = new ItemSeq();

    public Universe(){
        load();

        //load legacy research
        Events.on(ClientLoadEvent.class, e -> {
            if(Core.settings.has("unlocks")){
                LegacyIO.readResearch();
            }
        });

        //update base coverage on capture
        Events.on(SectorCaptureEvent.class, e -> {
            if(!net.client() && state.isCampaign()){
                state.getSector().planet.updateBaseCoverage();
            }
        });
    }

    /** Update regardless of whether the player is in the campaign. */
    public void updateGlobal(){
        //currently only updates one solar system
        updatePlanet(Planets.sun);
    }

    public int turn(){
        return turn;
    }

    private void updatePlanet(Planet planet){
        planet.position.setZero();
        planet.addParentOffset(planet.position);
        if(planet.parent != null){
            planet.position.add(planet.parent.position);
        }
        for(Planet child : planet.children){
            updatePlanet(child);
        }
    }

    /** Update planet rotations, global time and relevant state. */
    public void update(){

        //only update time when not in multiplayer
        if(!net.client()){
            secondCounter += Time.delta / 60f;
            turnCounter += Time.delta;

            //auto-run turns
            if(turnCounter >= turnDuration){
                turnCounter = 0;
                runTurn();
            }

            if(secondCounter >= 1){
                seconds += (int)secondCounter;
                secondCounter %= 1f;

                //save every few seconds
                if(seconds % 10 == 1){
                    save();
                }
            }
        }

        if(state.hasSector()){
            //update sector light
            float light = state.getSector().getLight();
            float alpha = Mathf.clamp(Mathf.map(light, 0f, 0.8f, 0.2f, 1f));

            //assign and map so darkness is not 100% dark
            state.rules.ambientLight.a = 1f - alpha;
            state.rules.lighting = !Mathf.equal(alpha, 1f);
        }
    }

    public void clearLoadoutInfo(){
        lastLoadout = null;
        lastLaunchResources = new ItemSeq();
        Core.settings.remove("launch-resources-seq");
        Core.settings.remove("lastloadout-core-shard");
        Core.settings.remove("lastloadout-core-nucleus");
        Core.settings.remove("lastloadout-core-foundation");
    }

    public ItemSeq getLaunchResources(){
        lastLaunchResources = Core.settings.getJson("launch-resources-seq", ItemSeq.class, ItemSeq::new);
        return lastLaunchResources;
    }

    public void updateLaunchResources(ItemSeq stacks){
        this.lastLaunchResources = stacks;
        Core.settings.putJson("launch-resources-seq", lastLaunchResources);
    }

    /** Updates selected loadout for future deployment. */
    public void updateLoadout(CoreBlock block, Schematic schem){
        Core.settings.put("lastloadout-" + block.name, schem.file == null ? "" : schem.file.nameWithoutExtension());
        lastLoadout = schem;
    }

    public Schematic getLastLoadout(){
        if(lastLoadout == null) lastLoadout = Loadouts.basicShard;
        return lastLoadout;
    }

    /** @return the last selected loadout for this specific core type. */
    public Schematic getLoadout(CoreBlock core){
        //for tools - schem
        if(schematics == null) return Loadouts.basicShard;

        //find last used loadout file name
        String file = Core.settings.getString("lastloadout-" + core.name, "");

        //use default (first) schematic if not found
        Seq<Schematic> all = schematics.getLoadouts(core);
        Schematic schem = all.find(s -> s.file != null && s.file.nameWithoutExtension().equals(file));

        return schem == null ? all.first() : schem;
    }

    /** Runs possible events. Resets event counter. */
    public void runTurn(){
        turn++;

        int newSecondsPassed = (int)(turnDuration / 60);

        //update relevant sectors
        for(Planet planet : content.planets()){
            for(Sector sector : planet.sectors){
                if(sector.hasSave() && sector.hasBase()){

                    //increment seconds passed for this sector by the time that just passed with this turn
                    if(!sector.isBeingPlayed()){

                        //increment time if attacked
                        if(sector.isAttacked()){
                            sector.info.secondsPassed += turnDuration/60f;
                        }

                        int wavesPassed = (int)(sector.info.secondsPassed*60f / sector.info.waveSpacing);
                        boolean attacked = sector.info.waves;

                        if(attacked){
                            sector.info.wavesPassed = wavesPassed;
                        }

                        float damage = attacked ? SectorDamage.getDamage(sector.info) : 0f;

                        //damage never goes down until the player visits the sector, so use max
                        sector.info.damage = Math.max(sector.info.damage, damage);

                        //check if the sector has been attacked too many times...
                        if(attacked && damage >= 0.999f){
                            //fire event for losing the sector
                            Events.fire(new SectorLoseEvent(sector));

                            //sector is dead.
                            sector.info.items.clear();
                            sector.info.damage = 1f;
                            sector.info.hasCore = false;
                            sector.info.production.clear();
                        }else if(attacked && wavesPassed > 0 && sector.info.winWave > 1 && sector.info.wave + wavesPassed >= sector.info.winWave && !sector.hasEnemyBase()){
                            //autocapture the sector
                            sector.info.waves = false;

                            //fire the event
                            Events.fire(new SectorCaptureEvent(sector));
                        }

                        float scl = sector.getProductionScale();

                        //export to another sector
                        if(sector.info.destination != null){
                            Sector to = sector.info.destination;
                            if(to.hasBase()){
                                ItemSeq items = new ItemSeq();
                                //calculated exported items to this sector
                                sector.info.export.each((item, stat) -> items.add(item, (int)(stat.mean * newSecondsPassed * scl)));
                                to.addItems(items);
                            }
                        }

                        //add production, making sure that it's capped
                        sector.info.production.each((item, stat) -> sector.info.items.add(item, Math.min((int)(stat.mean * newSecondsPassed * scl), sector.info.storageCapacity - sector.info.items.get(item))));

                        sector.saveInfo();
                    }

                    //queue random invasions
                    if(!sector.isAttacked() && turn > invasionGracePeriod && sector.info.hasSpawns){
                        //invasion chance depends on # of nearby bases
                        if(Mathf.chance(baseInvasionChance * sector.near().count(Sector::hasEnemyBase))){
                            int waveMax = Math.max(sector.info.winWave, sector.isBeingPlayed() ? state.wave : sector.info.wave + sector.info.wavesPassed) + Mathf.random(2, 5) * 5;

                            //assign invasion-related things
                            if(sector.isBeingPlayed()){
                                state.rules.winWave = waveMax;
                                state.rules.waves = true;
                            }else{
                                sector.info.winWave = waveMax;
                                sector.info.waves = true;
                                sector.saveInfo();
                            }

                            Events.fire(new SectorInvasionEvent(sector));
                        }
                    }
                }
            }
        }

        Events.fire(new TurnEvent());

        save();
    }

    /** This method is expensive to call; only do so sparingly. */
    public ItemSeq getGlobalResources(){
        ItemSeq count = new ItemSeq();

        for(Planet planet : content.planets()){
            for(Sector sector : planet.sectors){
                if(sector.hasSave()){
                    count.add(sector.items());
                }
            }
        }

        return count;
    }

    public void updateNetSeconds(int value){
        netSeconds = value;
    }

    public float secondsMod(float mod, float scale){
        return (seconds() / scale) % mod;
    }

    public int seconds(){
        //use networked seconds when playing as client
        return net.client() ? netSeconds : seconds;
    }

    public float secondsf(){
        return seconds() + secondCounter;
    }

    private void save(){
        Core.settings.put("utimei", seconds);
        Core.settings.put("turn", turn);
    }

    private void load(){
        seconds = Core.settings.getInt("utimei");
        turn = Core.settings.getInt("turn");
    }

}
