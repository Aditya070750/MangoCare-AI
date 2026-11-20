"""Shared care guidance for the website and generated reports."""

SOURCE = "https://ask.ifas.ufl.edu/publication/mg216"
NOTE = "AI predictions support observation. Confidence is a model score, and a photo alone cannot confirm a diagnosis or disease severity."
CARE = {
    "Anthracnose": {
        "description": "A fungal disease that can leave irregular dark brown or black spots on mango leaves. It can also affect flowers, twigs, and fruit.",
        "Causes": ["Colletotrichum fungi infect susceptible mango tissue.", "Rain splash and extended leaf wetness favor spread."],
        "Precautions": ["Water the soil and avoid wetting foliage or flowers.", "Thin dense growth so leaves dry sooner after rain."],
        "Treatment": ["Remove infected debris and prune affected twigs.", "Ask local agricultural extension whether a labeled treatment is appropriate."],
    },
    "Powdery Mildew": {
        "description": "A fungal disease that can leave a white, powder-like coating on young mango leaves, shoots, and flower clusters.",
        "Causes": ["Powdery mildew fungi produce spores on infected tissue.", "Tender leaves and flower clusters are especially susceptible."],
        "Precautions": ["Maintain airflow through the canopy.", "Check new shoots and flower clusters during flowering."],
        "Treatment": ["Remove visibly affected growth where practical.", "Ask local agricultural extension about suitable labeled options if symptoms spread."],
    },
    "Healthy": {
        "description": "The visible leaf tissue matched the model’s Healthy class.",
        "What to check": ["Look for a uniform color and intact leaf tissue.", "Inspect other leaves and flowers for symptoms outside the photo."],
        "Precautions": ["Water the root zone and keep foliage dry.", "Monitor new growth after changes in weather."],
        "Care": ["Continue normal watering and pruning.", "Seek local advice if persistent spots or powdery growth appear."],
    },
}
