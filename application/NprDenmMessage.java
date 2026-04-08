package org.eclipse.mosaic.app.npr;

import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;

public class NprDenmMessage extends V2xMessage {

    private final double velocidadeRecomendada;
    private final long tempoExpiracao;
    private final String eventId;

    public NprDenmMessage(MessageRouting routing, double velocidadeRecomendada, long tempoExpiracao, String eventId) {
        super(routing);
        this.velocidadeRecomendada = velocidadeRecomendada;
        this.tempoExpiracao = tempoExpiracao;
        this.eventId = eventId;
    }

    public double getVelocidadeRecomendada() {
        return velocidadeRecomendada;
    }

    public long getTempoExpiracao() {
        return tempoExpiracao;
    }

    public String getEventId() {
        return eventId;
    }

    @Override
    public EncodedPayload getPayload() {
        return new EncodedPayload(100, 800);
    }

    @Override
    public String toString() {
        return "NprDenmMessage{vel=" + velocidadeRecomendada
                + ", exp=" + tempoExpiracao
                + ", eventId=" + eventId + "}";
    }
}