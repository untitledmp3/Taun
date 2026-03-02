mostly vibecoded.
msg @videotaped if u have bugs or ask in taunahi thread

THIS ONLY WORKS WITH S-SHAPE CROP FARM. (u can use flowers/sugarcane if u disable etherwarp to glass roof and change autodirection for s-shape crop a bit)  

========= Taun+++ Commands =========  

--- Setup ---  
/pest setup / setspawn / setend / setspawntrigger  
FOLLOW THE SETUP [https://www.youtube.com/watch?v=Vm9ipIBbMgY](https://www.youtube.com/watch?v=Vm9ipIBbMgY)  
SCHEMATIC - [https://cdn.discordapp.com/attachments/1476600176884973630/1477131122897981603/Wheat_S-Shape_Taun.litematic](https://cdn.discordapp.com/attachments/1476600176884973630/1477131122897981603/Wheat_S-Shape_Taun.litematic?ex=69a3a50f&is=69a2538f&hm=f67ab5466b8f6dd2cf97b3d2aa1599e7bb51994cf86369bbb96f1b67e9936ee2&)
I REALLY RECOMMEND USING PLOT 16 TO PLOT 11 AS I CAN CONFIRM IT WORKS  
if u cant be asked making a custom farm design you COULD use taunahi's intermediate rewarper just make sure to disable coordinate triggers with /pest toggle coords (not tested)    

--- Modes ---  
/pest rodswap / wdswap / etherwarp / eqswap  
/pest rodswap rosedrag — (toggles rodswap compatibility for rosedrag rules)  
/pest wardrobe - will ask you what slots your 2 armor sets are in. (**REQUIRED FOR WARDROBE SWAP IMPORTANT**)  
/pest eqswap zorro (toggles zorro cape usage during jacobs event) **MAKE SURE U GIVE JACOBS EVENT ENOUGH PRIORITY IN TABLIST**  

--- Dynamic Rest ---  
/pest dynarest — toggles on or off  
/pest dynarest time — set dynamic rest time (e.g. 2h, 2.5h 90m)   
/pest dynarest breaktime minutes — how long before reconnecting  
/pest dynarest scriptoffset minutes — adds randomness  
/pest dynarest status — checks current settings and next disconnect

--- Utility ---  
/pest toggle chat / coords / leave empty for all — toggles triggers / coordinate triggers on or off  
/pest georgesell 1-10 — (treshold at which it triggers the sell) Toggle George slug auto-sell on/off  
/pest extrasell x — (treshold at which it triggers the sell) Toggle selling extra items using booster cookie menu (mantid claw, stereo, overclockers, larva, chips)  
/pest dropbooks x — (treshold at which it triggers the dropping) (only really useful for ironman)
/pest random 0-250ms — randomize all delays in triggers.txt by ±<ms>  
/pest reload / detect / files / debug / help   
/pest guidelay — change the equipping delay (useful for users with high ping)
/pest setetherwarp — lets u customize where u etherwarp to / where ur glass is
/pest status — shows the status of all commands / thresholds and whatnot  

=====================================

rodswap rules  
on rod cast summon mooshroom except if you have mooshroom summoned or if you have mosquito summoned   
on rod cast summon mosquito except if you have mosquito summoned or if you have hedgehog summoned  
on enter combat summon hedgehog  

rose dragon rodswap rules  
on rod cast summon mosquito  
on enter combat summon rose dragon    

wardrobe swap rules  
on equip mossy helianthus, equip hedgehog  
on equip mantid helianthus, equip mosquito  
on gain collection (crop that you are farming), equip mooshroom cow  
(u can also add on enter combat equip rosedrag in case you kill a pest that has the same collection as your crop)  

My Taunahi settings  
Activate Instantly = On  
Boost Efficiency = On (required i think)  
Use Sprayonator = On  
Empty Bag = On  
Empty At Start = Off  
Use Pest Trap = Optional  

Would recommend only using 1 or 2 plots.  
