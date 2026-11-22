/**
 * Resultado de uma operação sobre o sistema de reservas.
 * Parte do TAD compartilhado (Pessoas 3 e 4).
 */
public enum Resultado {
    OK("ok"),
    LOTADO("voo lotado"),
    VOO_INEXISTENTE("voo inexistente"),
    JA_RESERVADO("cliente ja tem assento neste voo"),
    NAO_ENCONTRADO("reserva nao encontrada"),
    NOME_INVALIDO("nome invalido");

    private final String mensagem;

    Resultado(String mensagem) {
        this.mensagem = mensagem;
    }

    public String mensagem() {
        return mensagem;
    }
}


// javac *.java
// java ReservasThreads                                   # interativo: 30 clientes, 3 voos x 8 assentos
// java ReservasThreads --batch -c 40 -v 3 -a 8 -l 200    # sem menu, mostra o tempo total
// java ReservasThreads --batch --sem-mutex --atraso-critico 2000 -c 40 -v 1 -a 8 -l 5