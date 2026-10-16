-- Boton de arrepentimiento (/arrepentimiento): pedidos de revocacion de una compra. Sin relacion con cuentas ni eventos:
-- el pedido se guarda aunque el email o el evento no existan (la respuesta no puede revelar si existen). El reembolso
-- se gestiona a mano desde Mercado Pago; status y handled_at quedan para cuando haya una accion en el superadmin.
CREATE TABLE withdrawal_request (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    message VARCHAR(1000) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    handled_at TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX idx_withdrawal_request_created_at ON withdrawal_request(created_at);
