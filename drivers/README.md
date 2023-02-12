## TH112xZB_Sinope_Hubitat.groovy 
Driver for the zigbee sinope thermostats. At the installation, <b>you must save the preferences and then click configure</b>. The first time configure is click, a error may be log, this is the only time you should see an error from this driver.

This driver allows to send via rule machine the outsite temperature to the thermostats using the notification capability of the thermostat. Moreover, the thermostat have an energy meter capabilities. This driver retrieve the energy value from the thermostat and estimate the cost of energy. Also, there is a slight delay (2 seconds) programmed for the heating set point. This delay make the thermostat tile from the Hubitat dashboard response more consistent.

Moreover, there is a command name "Remove Old State Variable". This allow you to remove the state variables that are no longer necessary. The image below shows the ones that are necessary. Some of those variables below may take about an hour to appers.

![image](https://user-images.githubusercontent.com/59889660/158215165-86ceeff0-2c5a-4a67-9525-d322058fc7a2.png)

## SP2600ZB_Sinope_Hubitat.groovy
This driver is for the sinope outlet/switch. It will most likely works with several others Zigbee outlet/switch. Almost identical to the default driver, it however evaluate the energy of the outlet/switch by retriving this information from the switch. This driver have been tested with the outlet device of Sinope, I belive it will also works with the switch (SW2500ZB).

## RM3250ZB_Sinope_Hubitat.groovy
This driver is for the sinope load control switch. Almost identical to the previous switch driver, except that the power divisor is not the same.

## RM3500ZB_Sinope_Hubitat.groovy 
This driver is for the sinope water heater controller. It includes an energy meter, water temperature attribute and the water leak sensor. This driver was possible thanks to the work of [Nelson Clark](https://github.com/NelsonClark).

## TH1300ZB_Sinope_Hubitat.groovy
This is a Beta release. Have been tested with the collaboration of nice people, since I don't have this thermostat, so it should work without mistake. Don't hesistate to write to me here or via the community of hubitat (samuel.c.auclair) to send error if there is some.

## TH1400ZB_Sinope_Hubitat.groovy
This is a Beta release. Have been tested with the collaboration of nice people, since I don't have this thermostat, so it should work without mistake. Don't hesistate to write to me here or via the community of hubitat (samuel.c.auclair) to send error if there is some.
