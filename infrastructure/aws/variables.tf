variable "region" {
  type    = string
  default = "us-east-1"
}

variable "name" {
  type    = string
  default = "aegistrace"
}

variable "environment" {
  type    = string
  default = "staging"

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "Environment must be staging or production."
  }
}

variable "certificate_arn" {
  type        = string
  default     = ""
  description = "ACM certificate ARN for the public edge. Required before an environment is called production."
}

variable "alarm_email" {
  type        = string
  default     = ""
  description = "Optional address for CloudWatch alarm email. Empty leaves alarms visible but unrouted."
}

variable "frontend_image" {
  type = string
}

variable "control_plane_image" {
  type = string
}

variable "runtime_image" {
  type = string
}

variable "worker_image" {
  type = string
}

variable "frontend_origin" {
  type        = string
  description = "Public https origin allowed to call the control plane."
}

variable "github_repository" {
  type    = string
  default = "Ganesh2325/AegisTrace"
}

variable "release_sha" {
  type    = string
  default = ""
}
