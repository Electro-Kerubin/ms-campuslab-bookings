# Caso normal (con entity/repository), como audit
.\scaffold.ps1 -Dominio audit

# Sin persistencia (bff, notify) - omite entity/ y repository/
.\scaffold.ps1 -Dominio notify -SinPersistencia

# Con messaging/producer además del consumer (bookings)
.\scaffold.ps1 -Dominio bookings -ConMessagingProducer