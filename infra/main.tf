locals {
  services = {
    ingestion = { port = 8081, rate = 100, in_flight = 64 }
    analytics = { port = 8082, rate = 10, in_flight = 8 }
  }
  active_services = var.enable_services ? local.services : {}
  kcl_application = "${var.name}-analytics"
  api_url         = "https://${var.api_hostname}"
}
