package org.eclipse.mosaic.app.npr;

import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.app.api.os.RoadSideUnitOperatingSystem;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class NprRsuApp extends AbstractApplication<RoadSideUnitOperatingSystem>
        implements CommunicationApplication {

    // Histerese
    private static final int LIMIAR_ALTO = 10;
    private static final int LIMIAR_BAIXO = 4;

    // Intervalos
    private static final long INTERVALO_AVALIACAO = 2_000_000_000L; // 2 s
    private static final long TTL_DENM = 5_000_000_000L;            // 5 s

    private boolean avisoAtivo = false;
    private final Set<String> veiculosDetectados = new HashSet<>();

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable();
        System.out.println("✅ RSU " + getOs().getId() + " online com Gestão Dinâmica!");
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + 1_000_000_000L, this);
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        String vehicleId = receivedV2xMessage.getMessage()
                .getRouting()
                .getSource()
                .getSourceName();

        veiculosDetectados.add(vehicleId);
    }

    @Override
    public void processEvent(Event event) throws Exception {
        int densidadeAtual = veiculosDetectados.size();

        if (!avisoAtivo && densidadeAtual >= LIMIAR_ALTO) {
            avisoAtivo = true;
            System.out.println("[ALERTA] Densidade ALTA (" + densidadeAtual + "). Ativando restrição.");
        } else if (avisoAtivo && densidadeAtual <= LIMIAR_BAIXO) {
            avisoAtivo = false;
            System.out.println("[FLUXO] Densidade BAIXA (" + densidadeAtual + "). Desativando restrição.");
        }

        if (avisoAtivo) {
            enviarAvisoObras(densidadeAtual);
        }

        veiculosDetectados.clear();
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + INTERVALO_AVALIACAO, this);
    }

    private void enviarAvisoObras(int densidadeAtual) {
        try {
            MessageRouting routing = getOs().getAdHocModule()
                    .createMessageRouting()
                    .topoBroadCast(1);

            long agora = getOs().getSimulationTime();
            long tempoExpiracao = agora + TTL_DENM;

            double velocidadeRecomendada = calcularVelocidadeRecomendada(densidadeAtual);
            String eventId = "WORKZONE-" + UUID.randomUUID();

            NprDenmMessage aviso = new NprDenmMessage(
                    routing,
                    velocidadeRecomendada,
                    tempoExpiracao,
                    eventId
            );

            getOs().getAdHocModule().sendV2xMessage(aviso);

            System.out.println("📢 RSU " + getOs().getId()
                    + " enviou DENM | vel=" + velocidadeRecomendada
                    + " m/s | densidade=" + densidadeAtual
                    + " | exp=" + tempoExpiracao
                    + " | id=" + eventId);

        } catch (Exception e) {
            System.out.println("Erro ao enviar DENM: " + e.getMessage());
        }
    }

    private double calcularVelocidadeRecomendada(int densidadeAtual) {
        // Exemplo simples coerente com a vossa Opção A:
        // muita densidade -> 30 km/h
        // média densidade -> 50 km/h
        // baixa densidade com aviso ativo -> 70 km/h

        if (densidadeAtual >= 16) {
            return 8.33;   // 30 km/h
        } else if (densidadeAtual >= 12) {
            return 13.89;  // 50 km/h
        } else {
            return 19.44;  // 70 km/h
        }
    }

    @Override
    public void onAcknowledgementReceived(ReceivedAcknowledgement acknowledgedMessage) {
    }

    @Override
    public void onCamBuilding(CamBuilder camBuilder) {
    }

    @Override
    public void onMessageTransmitted(V2xMessageTransmission v2xMessageTransmission) {
    }

    @Override
    public void onShutdown() {
        System.out.println("RSU " + getOs().getId() + " desligada.");
    }
}