/*
 * Copyright © 2018 Dennis Schulmeister-Zimolong
 * 
 * E-Mail: dhbw@windows3.de
 * Webseite: https://www.wpvs.de/
 * 
 * Dieser Quellcode ist lizenziert unter einer
 * Creative Commons Namensnennung 4.0 International Lizenz.
 */
package dhbwka.wwi.vertsys.pubsub.fahrzeug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Hauptklasse unseres kleinen Progrämmchens.
 *
 * Mit etwas Google-Maps-Erfahrung lassen sich relativ einfach eigene
 * Wegstrecken definieren. Man muss nur Rechtsklick auf einen Punkt machen und
 * "Was ist hier?" anklicken, um die Koordinaten zu sehen. Allerdings speichert
 * Goolge Maps eine Nachkommastelle mehr, als das ITN-Format erlaubt. :-)
 */
public class Main {

	public static void main(String[] args) throws Exception {
		// Fahrzeug-ID abfragen
		String vehicleId = Utils.askInput("Beliebige Fahrzeug-ID", "postauto");

		// Zu fahrende Strecke abfragen
		File workdir = new File("./waypoints");
		String[] waypointFiles = workdir.list((File dir, String name) -> {
			return name.toLowerCase().endsWith(".itn");
		});

		System.out.println();
		System.out.println("Aktuelles Verzeichnis: " + workdir.getCanonicalPath());
		System.out.println();
		System.out.println("Verfügbare Wegstrecken");
		System.out.println();

		for (int i = 0; i < waypointFiles.length; i++) {
			System.out.println("  [" + i + "] " + waypointFiles[i]);
		}

		System.out.println();
		int index = Integer.parseInt(Utils.askInput("Zu fahrende Strecke", "0"));

		List<WGS84> waypoints = parseItnFile(new File(workdir, waypointFiles[index]));

		// Adresse des MQTT-Brokers abfragen
		String mqttAddress = Utils.askInput("MQTT-Broker", Utils.MQTT_BROKER_ADDRESS);

		StatusMessage lastWill = new StatusMessage();
		lastWill.type = StatusType.CONNECTION_LOST;
		lastWill.vehicleId = vehicleId;
		lastWill.message = "Connection lost";

		MqttConnectOptions mco = new MqttConnectOptions();
		mco.setWill(Utils.MQTT_TOPIC_NAME, lastWill.message.getBytes(), 0, true);
		mco.setCleanSession(true);

		MqttClient client = new MqttClient(mqttAddress, vehicleId, new MemoryPersistence());
		client.connect();
		System.out.println("Connected to " + mqttAddress);

		StatusMessage status = new StatusMessage();
		status.type = StatusType.VEHICLE_READY;
		status.vehicleId = vehicleId;
		status.message = "Vehicle ready";

		MqttMessage message = new MqttMessage();
		message.setPayload(status.toJson());
		message.setQos(0);
		client.publish(Utils.MQTT_TOPIC_NAME, message);
		System.out.println("Sent ready status");

		Vehicle vehicle = new Vehicle(vehicleId, waypoints);
		vehicle.startVehicle();

		Timer timer = new Timer();
		timer.schedule(new TimerTask() {

			@Override
			public void run() {
				try {
					client.publish(Utils.MQTT_TOPIC_NAME + "/" + vehicleId,
							new MqttMessage(vehicle.getSensorData().toJson()));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}, 0, 1000);

		// Warten, bis das Programm beendet werden soll
		Utils.fromKeyboard.readLine();

		vehicle.stopVehicle();
		timer.cancel();
		System.out.println("Vehicle stopped");

		client.publish(Utils.MQTT_TOPIC_NAME, new MqttMessage(lastWill.toJson()));
		client.disconnect();
		System.out.println("Disconnected from " + mqttAddress);
	}

	/**
	 * Öffnet die in "filename" übergebene ITN-Datei und extrahiert daraus die
	 * Koordinaten für die Wegstrecke des Fahrzeugs. Das Dateiformat ist ganz
	 * simpel:
	 *
	 * <pre>
	 * 0845453|4902352|Point 1 |0|
	 * 0848501|4900249|Point 2 |0|
	 * 0849295|4899460|Point 3 |0|
	 * 0849796|4897723|Point 4 |0|
	 * </pre>
	 *
	 * Jede Zeile enthält einen Wegpunkt. Die Datenfelder einer Zeile werden durch |
	 * getrennt. Das erste Feld ist die "Longitude", das zweite Feld die "Latitude".
	 * Die Zahlen müssen durch 100_000.0 geteilt werden.
	 *
	 * @param file
	 *            ITN-Datei
	 * @return Liste mit Koordinaten
	 * @throws java.io.IOException
	 */
	public static List<WGS84> parseItnFile(File file) throws IOException {
		List<WGS84> waypoints = new ArrayList<>();
		BufferedReader br = new BufferedReader(new FileReader(file));
		String s;
		while ((s = br.readLine()) != null) {
			String str[] = s.split("\\|");
			try {
				if (str.length >= 2)
					waypoints.add(new WGS84(Double.parseDouble(str[1]) / 100000, Double.parseDouble(str[0]) / 100000));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return waypoints;
	}

}
